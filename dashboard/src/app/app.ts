import { Component, computed, effect, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AppwriteException } from 'appwrite';
import { AppwriteService } from './appwrite.service';
import { EnergyChart } from './energy-chart';
import { IdleWatcher } from './idle-watcher';
import { Energy, ENERGY_LABEL, InvoiceRow, PROVIDER_LABEL, ReadingRow, TariffRow } from './models';
import { invoiceFilename, kpi, series } from './stats';

@Component({
  selector: 'app-root',
  imports: [FormsModule, DatePipe, DecimalPipe, EnergyChart],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit, OnDestroy {
  protected readonly aw = inject(AppwriteService);
  private idle?: IdleWatcher;

  protected readonly energies: Energy[] = ['electricity', 'gas', 'water'];
  protected readonly ENERGY_LABEL = ENERGY_LABEL;
  protected readonly PROVIDER_LABEL = PROVIDER_LABEL;

  protected readonly ready = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly downloading = signal<string | null>(null);

  protected readonly readings = signal<ReadingRow[]>([]);
  protected readonly invoices = signal<InvoiceRow[]>([]);
  protected readonly tariffs = signal<TariffRow[]>([]);
  protected readonly selected = signal<Energy>('electricity');
  protected readonly showTable = signal(false);
  /** Filtre de la liste des factures : un fournisseur ("gdb", "ileo") ou tous. */
  protected readonly invoiceProvider = signal<string | 'all'>('all');

  protected email = '';
  protected password = '';

  protected readonly kpis = computed(() => this.energies.map((e) => kpi(this.readings(), this.invoices(), this.tariffs(), e)));
  protected readonly points = computed(() => series(this.readings(), this.selected()));
  /** Fournisseurs présents dans les factures, pour proposer le filtre. */
  protected readonly invoiceProviders = computed(() => [...new Set(this.invoices().map((i) => i.provider))].sort());
  protected readonly filteredInvoices = computed(() => {
    const p = this.invoiceProvider();
    return p === 'all' ? this.invoices() : this.invoices().filter((i) => i.provider === p);
  });
  /** Somme des factures affichées (montants absents ignorés). */
  protected readonly invoiceTotals = computed(() => {
    const list = this.filteredInvoices();
    return {
      count: list.length,
      amount: list.reduce((sum, i) => sum + (i.amount ?? 0), 0),
      due: list.reduce((sum, i) => sum + (i.amountDue ?? 0), 0),
    };
  });
  /** Gaz uniquement : au moins un mois a des heures de chauffe (thermostat Netatmo). */
  protected readonly hasHeating = computed(
    () => this.selected() === 'gas' && this.points().some((p) => p.heatingHours !== null),
  );
  protected readonly lastSync = computed(() => {
    const dates = this.readings().map((r) => r.$updatedAt).sort();
    return dates.at(-1) ?? null;
  });

  constructor() {
    // Connecté : on surveille l'inactivité. Déconnecté (par nous, par expiration ou par le serveur) : plus aucune
    // donnée personnelle ne reste en mémoire dans la page.
    effect(() => {
      if (this.aw.user()) {
        this.idle ??= new IdleWatcher(this.aw.idleMs, () => {
          void this.aw.expire(`Déconnecté après ${this.aw.idleMs / 60_000} min d'inactivité.`);
        });
        this.idle.start();
      } else {
        this.idle?.stop();
        this.readings.set([]);
        this.invoices.set([]);
        this.tariffs.set([]);
        this.invoiceProvider.set('all');
        this.email = '';
        this.password = '';
      }
    });
  }

  ngOnDestroy(): void {
    this.idle?.stop();
  }

  async ngOnInit(): Promise<void> {
    try {
      await this.aw.init();
      if (this.aw.user()) await this.load();
    } catch (e) {
      await this.fail(e);
    } finally {
      this.ready.set(true);
    }
  }

  protected async login(): Promise<void> {
    this.error.set(null);
    this.loading.set(true);
    try {
      await this.aw.login(this.email, this.password);
      this.password = '';
      await this.load();
    } catch (e) {
      await this.fail(e);
    } finally {
      this.loading.set(false);
    }
  }

  protected async logout(): Promise<void> {
    await this.aw.logout(); // les données en mémoire sont effacées par l'effet du constructeur
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [r, i, t] = await Promise.all([this.aw.readings(), this.aw.invoices(), this.aw.tariffs()]);
      this.tariffs.set(t);
      this.readings.set(r);
      this.invoices.set(i);
    } catch (e) {
      await this.fail(e);
    } finally {
      this.loading.set(false);
    }
  }

  protected async download(inv: InvoiceRow): Promise<void> {
    if (!inv.fileId) return;
    this.downloading.set(inv.$id);
    try {
      await this.aw.download(inv.fileId, invoiceFilename(inv));
    } catch (e) {
      await this.fail(e);
    } finally {
      this.downloading.set(null);
    }
  }

  /** Session refusée par le serveur : retour à l'écran de connexion. Toute autre erreur : message à l'écran. */
  private async fail(e: unknown): Promise<void> {
    if (this.aw.isUnauthorized(e) && this.aw.user()) {
      await this.aw.expire('Session expirée, reconnecte-toi.');
      return;
    }
    this.error.set(this.message(e));
  }

  private message(e: unknown): string {
    if (e instanceof AppwriteException && e.code === 429) return 'Trop de tentatives, réessaie dans quelques minutes.';
    return e instanceof Error ? e.message : 'Erreur inattendue';
  }
}
