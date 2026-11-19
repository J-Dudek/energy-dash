import { Injectable, computed, signal } from '@angular/core';
import type { Models } from 'appwrite';
import {
  Account,
  AppwriteException,
  Client,
  ID,
  Permission,
  Query,
  Role,
  TablesDB,
} from 'appwrite';
import { validateConfig } from './config';
import { createCredential, getCredential, passkeysSupported } from './passkey';
import { currentSubscription, deviceLabel, subscriptionId } from './push';
import type { AppConfig, InvoiceRow, PushDevice, ReadingRow, TariffRow } from './models';

/** Mémorise que le navigateur refuse le cookie de session (cookie tiers bloqué) : voir preferCookieSession. */
const COOKIE_MODE_KEY = 'conso.cookieMode';

/**
 * Accès Appwrite côté navigateur. La config (endpoint, projet...) est lue au démarrage
 * dans /config.json, générée par le conteneur à partir de ses variables d'environnement.
 *
 * Session : l'identifiant de session vit dans un cookie HttpOnly posé par Appwrite, que le JavaScript de la
 * page ne peut pas lire. Le SDK en garde aussi une copie dans localStorage (`cookieFallback`) quand le serveur
 * la propose ; on la retire dès que le cookie suffit (voir {@link preferCookieSession}).
 */
@Injectable({ providedIn: 'root' })
export class AppwriteService {
  private config!: AppConfig;
  private client!: Client;
  private account!: Account;
  private tables!: TablesDB;

  readonly user = signal<Models.User | null>(null);
  /**
   * Proposition de créer une passkey : levée après chaque connexion par mot de passe (même si l'utilisateur en a
   * déjà une : celle-ci peut être pour un autre appareil), jamais après une connexion par passkey.
   */
  readonly passkeyOffer = signal(false);
  /** Initiale affichée dans l'avatar (nom, à défaut e-mail). */
  readonly initial = computed(() => {
    const u = this.user();
    const label = [u?.name, u?.email].find((v) => v !== undefined && v.trim() !== '');
    return (label ?? '?').trim().charAt(0).toUpperCase();
  });
  /** Pourquoi on a été déconnecté (inactivité, session trop ancienne ou expirée) : affiché sur l'écran de connexion. */
  readonly notice = signal<string | null>(null);

  get idleMs(): number {
    return this.config.idleMinutes * 60_000;
  }

  /** Clé publique VAPID du serveur ; absente = notifications non configurées. */
  get vapidPublicKey(): string | undefined {
    return this.config.vapidPublicKey;
  }

  async init(): Promise<void> {
    const response = await fetch('config.json', { cache: 'no-store' });
    if (!response.ok) throw new Error('config.json introuvable');
    const raw: unknown = await response.json();
    this.config = validateConfig(raw);
    this.client = new Client().setEndpoint(this.config.endpoint).setProject(this.config.projectId);
    this.account = new Account(this.client);
    this.tables = new TablesDB(this.client);
    try {
      this.user.set(await this.account.get());
    } catch {
      this.user.set(null);
      return;
    }
    if (await this.tooOld()) {
      await this.expire('Session trop ancienne, reconnecte-toi.');
    } else {
      await this.preferCookieSession();
    }
  }

  async login(email: string, password: string): Promise<void> {
    this.notice.set(null);
    await this.account.createEmailPasswordSession({ email, password });
    await this.openSession();
    this.passkeyOffer.set(passkeysSupported());
  }

  /** Connexion par passkey : pas d'e-mail ni de mot de passe, l'appareil propose ses passkeys pour ce site. */
  async loginWithPasskey(): Promise<void> {
    this.notice.set(null);
    const challenge = await this.account.createPasskeyToken();
    const credential = await getCredential(challenge.publicKey);
    const token = await this.account.updatePasskeyToken({
      challengeId: challenge.$id,
      credential,
    });
    await this.account.createSession({ userId: token.userId, secret: token.secret });
    await this.openSession();
  }

  /** Change l'adresse e-mail ; Appwrite exige le mot de passe actuel et remet la vérification de l'adresse à zéro. */
  async updateEmail(email: string, password: string): Promise<void> {
    this.user.set(await this.account.updateEmail({ email, password }));
  }

  /**
   * Enregistre (ou rafraîchit) l'abonnement de cet appareil : le collecteur y lit où envoyer les notifications.
   * La ligne n'est lisible que par son propriétaire.
   */
  async savePushSubscription(subscription: PushSubscription): Promise<void> {
    const json = subscription.toJSON();
    const p256dh = json.keys?.['p256dh'];
    const auth = json.keys?.['auth'];
    const user = this.user();
    if (!user || !json.endpoint || !p256dh || !auth) throw new Error('Abonnement incomplet');
    const me = Role.user(user.$id);
    await this.tables.upsertRow({
      databaseId: this.config.databaseId,
      tableId: 'push_subscriptions',
      rowId: await subscriptionId(json.endpoint),
      data: { endpoint: json.endpoint, p256dh, auth, label: deviceLabel() },
      permissions: [Permission.read(me), Permission.update(me), Permission.delete(me)],
    });
  }

  async deletePushSubscription(endpoint: string): Promise<void> {
    try {
      await this.tables.deleteRow({
        databaseId: this.config.databaseId,
        tableId: 'push_subscriptions',
        rowId: await subscriptionId(endpoint),
      });
    } catch (e) {
      if (!(e instanceof AppwriteException && e.code === 404)) throw e;
    }
  }

  /** Les appareils de l'utilisateur abonnés aux notifications (les lignes des autres lui sont invisibles). */
  async pushDevices(): Promise<PushDevice[]> {
    const res = await this.tables.listRows({
      databaseId: this.config.databaseId,
      tableId: 'push_subscriptions',
      queries: [Query.orderDesc('$updatedAt'), Query.limit(50)],
    });
    return res.rows.map((r) => {
      const row = r as unknown as { $id: string; $updatedAt: string; label?: string | null };
      return { id: row.$id, label: row.label ?? 'Appareil', lastSeen: row.$updatedAt };
    });
  }

  async deletePushDevice(id: string): Promise<void> {
    await this.tables.deleteRow({
      databaseId: this.config.databaseId,
      tableId: 'push_subscriptions',
      rowId: id,
    });
  }

  /** Après connexion : si cet appareil est déjà abonné, on remet sa ligne à jour (clés renouvelées, ligne purgée). */
  async syncPushSubscription(): Promise<void> {
    if (
      !this.vapidPublicKey ||
      !('Notification' in window) ||
      Notification.permission !== 'granted'
    )
      return;
    try {
      const subscription = await currentSubscription();
      if (subscription) await this.savePushSubscription(subscription);
    } catch {
      // pas bloquant : l'utilisateur peut réactiver depuis la page Profil
    }
  }

  async passkeys(): Promise<Models.Passkey[]> {
    return (await this.account.listPasskeys()).passkeys;
  }

  /** Ajoute une passkey à l'utilisateur connecté (Appwrite exige une connexion de moins de 10 minutes). */
  async addPasskey(name: string): Promise<void> {
    const challenge = await this.account.createPasskey({ passkeyId: ID.unique(), name });
    const credential = await createCredential(challenge.publicKey);
    await this.account.updatePasskeyVerification({ passkeyId: challenge.passkeyId, credential });
  }

  async deletePasskey(passkeyId: string): Promise<void> {
    await this.account.deletePasskey({ passkeyId });
  }

  /**
   * Fin d'une connexion : on règle d'abord la question du cookie *puis* on annonce l'utilisateur. Dès que `user`
   * est posé, le tableau de bord s'affiche et charge ses données : si la copie de secours de la session était
   * retirée à ce moment-là (cookie tiers bloqué, Chrome), ces requêtes partiraient sans session et on serait
   * déconnecté aussitôt (« Session expirée »).
   */
  private async openSession(): Promise<void> {
    const user = await this.account.get();
    await this.preferCookieSession(true);
    this.user.set(user);
  }

  async logout(): Promise<void> {
    try {
      await this.account.deleteSession({ sessionId: 'current' });
    } finally {
      this.dropLocalCopy();
      this.passkeyOffer.set(false);
      this.user.set(null);
    }
  }

  /** Déconnexion imposée (inactivité, session expirée) : on ferme la session côté serveur et on explique pourquoi. */
  async expire(reason: string): Promise<void> {
    try {
      await this.logout();
    } catch {
      // réseau coupé : la session locale est déjà effacée, le serveur l'invalidera à son échéance
    }
    this.notice.set(reason);
  }

  /** Session refusée par Appwrite (expirée, révoquée) : à traiter comme une déconnexion, pas comme une panne. */
  isUnauthorized(e: unknown): boolean {
    return e instanceof AppwriteException && e.code === 401;
  }

  /** Durée maximale de session côté navigateur, indépendante de l'activité. */
  private async tooOld(): Promise<boolean> {
    try {
      const s = await this.account.getSession({ sessionId: 'current' });
      return Date.now() - Date.parse(s.$createdAt) > this.config.maxSessionHours * 3_600_000;
    } catch {
      return false;
    }
  }

  /**
   * Retire la copie de la session de localStorage (lisible par tout script de la page, donc par une éventuelle
   * faille XSS) si le cookie HttpOnly suffit à rester connecté. Sinon (cookies bloqués, domaines sans lien),
   * on la remet : mieux vaut une session fonctionnelle qu'une application inutilisable.
   */
  private async preferCookieSession(retest = false): Promise<void> {
    let copy: string | null;
    try {
      if (retest) localStorage.removeItem(COOKIE_MODE_KEY);
      // Déjà constaté que ce navigateur refuse le cookie : inutile de le retester (et de provoquer un 401 dans la
      // console) à chaque rechargement. Le test est refait à la prochaine connexion.
      if (localStorage.getItem(COOKIE_MODE_KEY) === 'fallback') return;
      copy = localStorage.getItem('cookieFallback');
      if (copy === null) return;
      localStorage.removeItem('cookieFallback');
    } catch {
      return; // stockage indisponible
    }
    try {
      await this.account.get();
    } catch {
      try {
        localStorage.setItem('cookieFallback', copy);
        localStorage.setItem(COOKIE_MODE_KEY, 'fallback');
      } catch {
        // rien de plus à faire
      }
    }
  }

  private dropLocalCopy(): void {
    try {
      localStorage.removeItem('cookieFallback');
      localStorage.removeItem(COOKIE_MODE_KEY);
    } catch {
      // stockage indisponible
    }
  }

  async readings(): Promise<ReadingRow[]> {
    return this.listAll<ReadingRow>('readings', [Query.orderAsc('periodEnd')]);
  }

  async invoices(): Promise<InvoiceRow[]> {
    return this.listAll<InvoiceRow>('invoices', [Query.orderDesc('date')]);
  }

  /** Tarifs du contrat. La table n'existe qu'après la première collecte récente : on tolère son absence. */
  async tariffs(): Promise<TariffRow[]> {
    try {
      return await this.listAll<TariffRow>('tariffs', [Query.orderDesc('validFrom')]);
    } catch {
      return [];
    }
  }

  /**
   * Télécharge un PDF du bucket. On passe par un JWT plutôt qu'un simple lien :
   * ça marche même si le cookie de session Appwrite n'est pas envoyé (autre domaine).
   */
  async download(fileId: string, filename: string): Promise<void> {
    const { jwt } = await this.account.createJWT();
    const url = `${this.config.endpoint}/storage/buckets/${this.config.bucketId}/files/${encodeURIComponent(fileId)}/download`;
    const resp = await fetch(url, {
      headers: { 'X-Appwrite-Project': this.config.projectId, 'X-Appwrite-JWT': jwt },
    });
    if (!resp.ok) throw new Error(`Téléchargement impossible (HTTP ${resp.status})`);
    const blobUrl = URL.createObjectURL(await resp.blob());
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filename;
    a.click();
    setTimeout(() => {
      URL.revokeObjectURL(blobUrl);
    }, 10_000);
  }

  private async listAll<T>(tableId: string, queries: string[]): Promise<T[]> {
    const out: T[] = [];
    const page = 1000; // l'instance accepte jusqu'à 5000 : 1 000 relevés = 2 requêtes au lieu de 11
    for (let offset = 0; ; offset += page) {
      const res = await this.tables.listRows({
        databaseId: this.config.databaseId,
        tableId,
        queries: [...queries, Query.limit(page), Query.offset(offset)],
      });
      out.push(...(res.rows as unknown as T[]));
      if (res.rows.length < page) return out;
    }
  }
}
