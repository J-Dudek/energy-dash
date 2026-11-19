import type { Energy, InvoiceRow, ReadingRow, TariffRow } from './models';

const DAY = 86_400_000;

export interface Point {
  label: string; // libellé d'axe : "nov. 25" ou "13/11/26"
  date: Date; // fin de période
  value: number; // kWh (élec, gaz) ou litres/jour (eau)
  hp: number | null;
  hc: number | null;
  temperature: number | null;
  raw: number; // quantité facturée/mesurée (kWh ou m³)
  days: number;
  heatingHours: number | null; // gaz : heures de marche de la chaudière sur le mois (Netatmo)
  heatingDays: number; // jours du mois couverts par les relevés Netatmo
  monthDays: number; // jours du mois
  kwhPerHeatingHour: number | null; // gaz : kWh / h de chauffe, mois complets uniquement
}

export interface Kpi {
  energy: Energy;
  latest: Point | null;
  previousYear: Point | null;
  deltaPct: number | null; // évolution vs même période N-1
  unit: string;
  lastInvoice: InvoiceRow | null;
  totalLast12: number | null; // kWh ou m³ sur 12 mois glissants
  unitPrice: number | null; // € / kWh (élec, gaz) ou € / m³ (eau)
  unitPriceUnit: string;
  unitPriceHint: string;
}

const monthFmt = new Intl.DateTimeFormat('fr-FR', { month: 'short', year: '2-digit' });
const dayFmt = new Intl.DateTimeFormat('fr-FR', {
  day: '2-digit',
  month: '2-digit',
  year: '2-digit',
});

/** Électricité et gaz : un point par mois. Eau : un point par facture, ramené en litres/jour. */
export function series(rows: ReadingRow[], energy: Energy): Point[] {
  const heating =
    energy === 'gas' ? heatingByMonth(rows) : new Map<string, { hours: number; days: number }>();
  return rows
    .filter((r) => r.energy === energy)
    .map((r) => {
      const start = new Date(r.periodStart);
      const end = new Date(r.periodEnd);
      const days = Math.max(1, Math.round((end.getTime() - start.getTime()) / DAY) + 1);
      const isWater = energy === 'water';
      return {
        label: isWater ? dayFmt.format(end) : monthFmt.format(end),
        date: end,
        value: isWater ? Math.round((r.quantity * 1000) / days) : r.quantity,
        hp: r.hp,
        hc: r.hc,
        temperature: r.avgTemperature,
        raw: r.quantity,
        days,
        ...heatingOf(r, heating),
      };
    })
    .sort((a, b) => a.date.getTime() - b.date.getTime());
}

/** Heures de chauffe et nombre de jours relevés, par mois ("2025-11"), à partir des relevés journaliers Netatmo. */
function heatingByMonth(rows: ReadingRow[]): Map<string, { hours: number; days: number }> {
  const out = new Map<string, { hours: number; days: number }>();
  for (const r of rows) {
    if (r.energy !== 'heating') continue;
    const key = r.periodEnd.slice(0, 7);
    const m = out.get(key) ?? { hours: 0, days: 0 };
    m.hours += r.quantity;
    m.days += 1;
    out.set(key, m);
  }
  return out;
}

function heatingOf(
  r: ReadingRow,
  heating: Map<string, { hours: number; days: number }>,
): Pick<Point, 'heatingHours' | 'heatingDays' | 'monthDays' | 'kwhPerHeatingHour'> {
  const key = r.periodEnd.slice(0, 7);
  const [year = 0, month = 1] = key.split('-').map(Number);
  const monthDays = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const h = heating.get(key);
  if (!h) return { heatingHours: null, heatingDays: 0, monthDays, kwhPerHeatingHour: null };
  const complete = h.days >= monthDays;
  return {
    heatingHours: Math.round(h.hours * 10) / 10,
    heatingDays: h.days,
    monthDays,
    // un ratio sur un mois partiel mélangerait un mois de gaz et quelques jours de chauffe
    kwhPerHeatingHour: complete && h.hours > 0 ? r.quantity / h.hours : null,
  };
}

export function kpi(
  rows: ReadingRow[],
  invoices: InvoiceRow[],
  tariffs: TariffRow[],
  energy: Energy,
): Kpi {
  const pts = series(rows, energy);
  const latest = pts.at(-1) ?? null;
  let previousYear: Point | null = null;
  if (latest) {
    // période la plus proche de "un an avant", à ±45 jours près
    const target = latest.date.getTime() - 365 * DAY;
    previousYear =
      pts
        .filter((p) => Math.abs(p.date.getTime() - target) <= 45 * DAY)
        .sort(
          (a, b) => Math.abs(a.date.getTime() - target) - Math.abs(b.date.getTime() - target),
        )[0] ?? null;
  }
  const deltaPct =
    latest && previousYear && previousYear.value > 0
      ? Math.round(((latest.value - previousYear.value) / previousYear.value) * 100)
      : null;

  let totalLast12: number | null = null;
  if (latest) {
    const from = latest.date.getTime() - 365 * DAY;
    const inWindow = pts.filter((p) => p.date.getTime() > from);
    totalLast12 = inWindow.length ? inWindow.reduce((s, p) => s + p.raw, 0) : null;
  }

  const price = unitPrice(invoices, tariffs, energy, pts);

  return {
    energy,
    latest,
    previousYear,
    deltaPct,
    unit: energy === 'water' ? 'L/jour' : 'kWh',
    lastInvoice: latestInvoice(invoices, energy),
    totalLast12,
    unitPrice: price.value,
    unitPriceUnit: energy === 'water' ? '€/m³' : '€/kWh',
    unitPriceHint: price.hint,
  };
}

/** Tarif en vigueur : le plus récent dont la date d'effet est passée (à défaut, le plus récent). */
function currentTariff(tariffs: TariffRow[], energy: Energy): TariffRow | null {
  const own = tariffs
    .filter((t) => t.energy === energy)
    .sort((a, b) => b.validFrom.localeCompare(a.validFrom));
  const now = new Date().toISOString();
  return own.find((t) => t.validFrom <= now) ?? own[0] ?? null;
}

const eur = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 3, maximumFractionDigits: 4 });

/**
 * Prix du kWh / du m³ payé, TTC, hors abonnement.
 * - Élec : tarifs HP/HC du contrat, pondérés par la répartition HP/HC des 12 derniers mois.
 * - Gaz : prix du kWh du contrat.
 * - Eau : montant / volume de la dernière facture Iléo qui a les deux (tout compris).
 */
function unitPrice(
  invoices: InvoiceRow[],
  tariffs: TariffRow[],
  energy: Energy,
  pts: Point[],
): { value: number | null; hint: string } {
  const none = { value: null, hint: '' };
  if (energy === 'water') {
    const latestFirst = invoices
      .filter((i) => i.energy === 'water')
      .sort((a, b) => b.date.localeCompare(a.date));
    for (const i of latestFirst) {
      if (i.amount !== null && i.amount > 0 && i.volume !== null && i.volume > 0) {
        return { value: i.amount / i.volume, hint: 'dernière facture, tout compris' };
      }
    }
    return none;
  }
  const t = currentTariff(tariffs, energy);
  if (!t) return none;
  if (t.kwh !== null) return { value: t.kwh, hint: 'tarif du contrat' };

  const latest = pts.at(-1);
  if (!latest || t.hp === null || t.hc === null) return none;
  const from = latest.date.getTime() - 365 * DAY;
  let hp = 0;
  let hc = 0;
  for (const p of pts) {
    if (p.date.getTime() > from && p.hp !== null && p.hc !== null) {
      hp += p.hp;
      hc += p.hc;
    }
  }
  if (hp + hc === 0) return none;
  const pctHc = Math.round((hc / (hp + hc)) * 100);
  return {
    value: (hp * t.hp + hc * t.hc) / (hp + hc),
    hint: `HP ${eur.format(t.hp)} · HC ${eur.format(t.hc)} (${pctHc} % en HC)`,
  };
}

/** Dernière facture d'une énergie. L'offre duale Gaz de Bordeaux peut tout regrouper sur la facture élec. */
export function latestInvoice(invoices: InvoiceRow[], energy: Energy): InvoiceRow | null {
  const own = invoices.filter((i) => i.energy === energy && i.fileId);
  const latestOwn = own.sort((a, b) => b.date.localeCompare(a.date))[0];
  if (latestOwn) return latestOwn;
  if (energy === 'gas') {
    const dual = invoices.filter((i) => i.provider === 'gdb' && i.fileId);
    return dual.sort((a, b) => b.date.localeCompare(a.date))[0] ?? null;
  }
  return null;
}

export function invoiceFilename(i: InvoiceRow): string {
  // le numéro vient du fournisseur : le nom de fichier ne doit contenir ni séparateur de chemin ni caractère spécial
  return `${i.provider}-${i.energy}-${i.date.slice(0, 10)}-${i.number}.pdf`.replace(
    /[^A-Za-z0-9._-]/g,
    '_',
  );
}
