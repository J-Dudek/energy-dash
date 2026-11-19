import type { OnInit } from '@angular/core';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AppwriteService } from './appwrite.service';
import { EnergyChart } from './energy-chart';
import { DashboardStore } from './dashboard-store';
import { errorMessage } from './errors';
import { InstallService } from './install';
import { defaultPasskeyName, isPasskeyCancelled } from './passkey';
import type { Energy, InvoiceRow } from './models';
import { ENERGY_LABEL, PROVIDER_LABEL } from './models';
import { invoiceFilename, kpi, series } from './stats';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, DatePipe, DecimalPipe, EnergyChart],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit {
  protected readonly aw = inject(AppwriteService);
  private readonly store = inject(DashboardStore);
  protected readonly install = inject(InstallService);
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.queryParamMap, { requireSync: true });
  private handledLink = '';

  protected readonly energies: Energy[] = ['electricity', 'gas', 'water'];
  protected readonly ENERGY_LABEL = ENERGY_LABEL;
  protected readonly PROVIDER_LABEL = PROVIDER_LABEL;

  protected readonly downloading = signal<string | null>(null);
  /** Facture mise en évidence après un lien de notification. */
  protected readonly highlight = signal<string | null>(null);
  protected readonly offerBusy = signal(false);
  protected readonly offerMessage = signal<string | null>(null);

  protected readonly loading = this.store.loading;
  protected readonly error = this.store.error;
  protected readonly readings = this.store.readings;
  protected readonly invoices = this.store.invoices;
  protected readonly tariffs = this.store.tariffs;
  protected readonly selected = this.store.selected;
  protected readonly showTable = this.store.showTable;
  protected readonly invoiceProvider = this.store.invoiceProvider;

  protected readonly kpis = computed(() =>
    this.energies.map((e) => kpi(this.readings(), this.invoices(), this.tariffs(), e)),
  );
  protected readonly points = computed(() => series(this.readings(), this.selected()));
  /** Fournisseurs présents dans les factures, pour proposer le filtre. */
  protected readonly invoiceProviders = computed(() =>
    [...new Set(this.invoices().map((i) => i.provider))].sort(),
  );
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
    const dates = this.readings()
      .map((r) => r.$updatedAt)
      .sort();
    return dates.at(-1) ?? null;
  });

  constructor() {
    // Lien venu d'une notification ou d'un raccourci (/?facture=…, /?vue=…, /?energie=…) : une fois les données
    // là, on se place sur la bonne vue. Les paramètres ne servent qu'à choisir parmi des valeurs connues.
    effect(() => {
      const p = this.params();
      if (this.invoices().length === 0 && this.readings().length === 0) return;
      const key = ['vue', 'facture', 'energie'].map((k) => p.get(k) ?? '').join('|');
      if (key === '||' || key === this.handledLink) return;
      this.handledLink = key;

      const energy = this.energies.find((e) => e === p.get('energie'));
      if (energy) this.selected.set(energy);

      const invoice = this.invoices().find((i) => i.$id === p.get('facture'));
      if (invoice) {
        this.invoiceProvider.set('all');
        this.highlight.set(invoice.$id);
        setTimeout(() => {
          this.highlight.set(null);
        }, 4000);
      }

      let target: 'factures' | 'graphique' | null = null;
      if (invoice || p.get('vue') === 'factures') target = 'factures';
      else if (energy || p.get('vue') === 'graphique') target = 'graphique';
      if (target) {
        setTimeout(() => {
          const row = invoice ? document.getElementById('facture-' + invoice.$id) : null;
          const el = row ?? document.getElementById(target);
          el?.scrollIntoView({ behavior: 'smooth', block: invoice ? 'center' : 'start' });
        }, 80);
      }
    });
  }

  ngOnInit(): void {
    void this.store.load();
  }

  protected async load(): Promise<void> {
    await this.store.load(true);
  }

  protected async createPasskey(): Promise<void> {
    this.offerBusy.set(true);
    this.offerMessage.set(null);
    try {
      await this.aw.addPasskey(defaultPasskeyName());
      this.aw.passkeyOffer.set(false);
    } catch (e) {
      this.offerMessage.set(isPasskeyCancelled(e) ? 'Création annulée.' : errorMessage(e));
    } finally {
      this.offerBusy.set(false);
    }
  }

  protected dismissOffer(): void {
    this.aw.passkeyOffer.set(false);
    this.offerMessage.set(null);
  }

  protected async download(inv: InvoiceRow): Promise<void> {
    if (!inv.fileId) return;
    this.downloading.set(inv.$id);
    try {
      await this.aw.download(inv.fileId, invoiceFilename(inv));
    } catch (e) {
      await this.store.fail(e);
    } finally {
      this.downloading.set(null);
    }
  }
}
