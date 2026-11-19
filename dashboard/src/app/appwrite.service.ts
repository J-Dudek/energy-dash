import { Injectable, signal } from '@angular/core';
import type { Models } from 'appwrite';
import { Account, AppwriteException, Client, Query, TablesDB } from 'appwrite';
import { validateConfig } from './config';
import type { AppConfig, InvoiceRow, ReadingRow, TariffRow } from './models';

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
  /** Pourquoi on a été déconnecté (inactivité, session trop ancienne ou expirée) : affiché sur l'écran de connexion. */
  readonly notice = signal<string | null>(null);

  get idleMs(): number {
    return this.config.idleMinutes * 60_000;
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
    this.user.set(await this.account.get());
    await this.preferCookieSession();
  }

  async logout(): Promise<void> {
    try {
      await this.account.deleteSession({ sessionId: 'current' });
    } finally {
      this.dropLocalCopy();
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
  private async preferCookieSession(): Promise<void> {
    let copy: string | null;
    try {
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
      } catch {
        // rien de plus à faire
      }
    }
  }

  private dropLocalCopy(): void {
    try {
      localStorage.removeItem('cookieFallback');
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
    const page = 100;
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
