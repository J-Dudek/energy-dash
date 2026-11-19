import { Injectable, effect, inject, signal } from '@angular/core';
import { AppwriteService } from './appwrite.service';
import { errorMessage } from './errors';
import type { Energy, InvoiceRow, ReadingRow, TariffRow } from './models';

/**
 * Données et choix d'affichage du tableau de bord, gardés hors du composant : quand on passe par la page Profil
 * et qu'on revient, rien n'est rechargé ni réinitialisé. Tout est effacé à la déconnexion.
 */
@Injectable({ providedIn: 'root' })
export class DashboardStore {
  private readonly aw = inject(AppwriteService);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly readings = signal<ReadingRow[]>([]);
  readonly invoices = signal<InvoiceRow[]>([]);
  readonly tariffs = signal<TariffRow[]>([]);
  readonly selected = signal<Energy>('electricity');
  readonly showTable = signal(false);
  /** Filtre de la liste des factures : un fournisseur ("gdb", "ileo") ou tous. */
  readonly invoiceProvider = signal<string>('all');

  private loaded = false;

  constructor() {
    // Plus d'utilisateur (déconnexion, expiration) : plus aucune donnée personnelle en mémoire.
    effect(() => {
      if (this.aw.user()) return;
      this.loaded = false;
      this.readings.set([]);
      this.invoices.set([]);
      this.tariffs.set([]);
      this.selected.set('electricity');
      this.showTable.set(false);
      this.invoiceProvider.set('all');
      this.error.set(null);
    });
  }

  /** Charge les données une seule fois par session ; `force` pour « Actualiser ». */
  async load(force = false): Promise<void> {
    if (this.loaded && !force) return;
    this.loading.set(true);
    this.error.set(null);
    try {
      const [r, i, t] = await Promise.all([
        this.aw.readings(),
        this.aw.invoices(),
        this.aw.tariffs(),
      ]);
      this.tariffs.set(t);
      this.readings.set(r);
      this.invoices.set(i);
      this.loaded = true;
    } catch (e) {
      await this.fail(e);
    } finally {
      this.loading.set(false);
    }
  }

  /** Session refusée par le serveur : retour à l'écran de connexion. Toute autre erreur : message à l'écran. */
  async fail(e: unknown): Promise<void> {
    if (this.aw.isUnauthorized(e) && this.aw.user()) {
      await this.aw.expire('Session expirée, reconnecte-toi.');
      return;
    }
    this.error.set(errorMessage(e));
  }
}
