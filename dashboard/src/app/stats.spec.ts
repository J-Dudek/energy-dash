import { describe, expect, it } from 'vitest';
import { InvoiceRow, ReadingRow, TariffRow } from './models';
import { invoiceFilename, kpi, series } from './stats';

const reading = (over: Partial<ReadingRow>): ReadingRow => ({
  $id: 'r', provider: 'gdb', energy: 'gas', granularity: 'month', periodStart: '2026-01-01T12:00:00.000+00:00',
  periodEnd: '2026-01-31T12:00:00.000+00:00', quantity: 100, unit: 'kWh', hp: null, hc: null, avgTemperature: null,
  $updatedAt: '2026-02-01T00:00:00.000+00:00', ...over,
});

const heatingDay = (day: number, hours: number): ReadingRow =>
  reading({ energy: 'heating', granularity: 'day', unit: 'h', quantity: hours,
    periodStart: `2026-01-${String(day).padStart(2, '0')}T12:00:00.000+00:00`,
    periodEnd: `2026-01-${String(day).padStart(2, '0')}T12:00:00.000+00:00` });

const tariff = (over: Partial<TariffRow>): TariffRow => ({
  $id: 't', provider: 'gdb', energy: 'gas', validFrom: '2025-06-01T12:00:00.000+00:00', offer: null, kwh: null, hp: null,
  hc: null, subscriptionMonthly: null, ...over,
});

const invoice = (over: Partial<InvoiceRow>): InvoiceRow => ({
  $id: 'i', provider: 'ileo', energy: 'water', number: 'N1', date: '2026-11-13T12:00:00.000+00:00', amount: null,
  amountDue: null, volume: null, fileId: null, ...over,
});

describe('chauffe (Netatmo) rapprochée du gaz', () => {
  const month = reading({ quantity: 1_550 }); // janvier : 31 jours

  it('mois complet : heures, jours couverts et kWh par heure de chauffe', () => {
    const rows = [month, ...Array.from({ length: 31 }, (_, i) => heatingDay(i + 1, 2))];

    const [p] = series(rows, 'gas');

    expect(p.heatingHours).toBe(62);
    expect(p.heatingDays).toBe(31);
    expect(p.monthDays).toBe(31);
    expect(p.kwhPerHeatingHour).toBe(25);
  });

  it('mois partiel : pas de ratio (il mélangerait un mois de gaz et quelques jours de chauffe)', () => {
    const rows = [month, heatingDay(1, 2), heatingDay(2, 3)];

    const [p] = series(rows, 'gas');

    expect(p.heatingHours).toBe(5);
    expect(p.heatingDays).toBe(2);
    expect(p.kwhPerHeatingHour).toBeNull();
  });

  it('sans relevé de chauffe : rien', () => {
    const [p] = series([month], 'gas');
    expect(p.heatingHours).toBeNull();
    expect(p.kwhPerHeatingHour).toBeNull();
  });

  it('la chauffe n\'apparaît pas comme une série à part dans les autres énergies', () => {
    const rows = [month, heatingDay(1, 2)];
    expect(series(rows, 'electricity')).toHaveLength(0);
    expect(series(rows, 'gas')).toHaveLength(1);
  });
});

describe('prix payé', () => {
  it('gaz : prix du kWh du contrat', () => {
    const k = kpi([reading({})], [], [tariff({ kwh: 0.1093 })], 'gas');
    expect(k.unitPrice).toBe(0.1093);
    expect(k.unitPriceUnit).toBe('€/kWh');
  });

  it('électricité : tarifs HP/HC pondérés par la consommation réelle', () => {
    const rows = [reading({ energy: 'electricity', quantity: 400, hp: 100, hc: 300 })];
    const k = kpi(rows, [], [tariff({ energy: 'electricity', hp: 0.2, hc: 0.1 })], 'electricity');
    expect(k.unitPrice).toBeCloseTo((100 * 0.2 + 300 * 0.1) / 400, 6);
  });

  it('eau : montant / volume de la dernière facture qui a les deux', () => {
    const invoices = [
      invoice({ $id: 'old', date: '2025-06-01T12:00:00.000+00:00', amount: 100, volume: 50 }),
      invoice({ $id: 'new', amount: 295.87, volume: 64 }),
      invoice({ $id: 'incomplete', date: '2026-12-01T12:00:00.000+00:00', amount: null, volume: 10 }),
    ];
    const k = kpi([reading({ energy: 'water', quantity: 64, unit: 'm3' })], invoices, [], 'water');
    expect(k.unitPrice).toBeCloseTo(295.87 / 64, 6);
  });

  it('prend le tarif en vigueur, pas un tarif futur', () => {
    const k = kpi([reading({})], [], [
      tariff({ $id: 'futur', validFrom: '2999-01-01T12:00:00.000+00:00', kwh: 9 }),
      tariff({ $id: 'actuel', validFrom: '2025-06-01T12:00:00.000+00:00', kwh: 0.1 }),
    ], 'gas');
    expect(k.unitPrice).toBe(0.1);
  });

  it('pas de tarif, pas de prix', () => {
    expect(kpi([reading({})], [], [], 'gas').unitPrice).toBeNull();
  });
});

describe('invoiceFilename', () => {
  it('neutralise tout ce qui pourrait sortir du dossier de téléchargement', () => {
    const name = invoiceFilename(invoice({ number: '../../etc/passwd"; rm -rf /' }));
    expect(name).toMatch(/^[A-Za-z0-9._-]+$/);
    expect(name).not.toContain('/');
  });

  it('garde un nom lisible pour une facture normale', () => {
    expect(invoiceFilename(invoice({ number: '1008608251' }))).toBe('ileo-water-2026-11-13-1008608251.pdf');
  });
});
