export type Energy = 'electricity' | 'gas' | 'water';
/** Les relevés contiennent aussi le temps de chauffe (Netatmo), qui n'est pas une énergie facturée. */
export type ReadingEnergy = Energy | 'heating';

export interface AppConfig {
  endpoint: string;
  projectId: string;
  databaseId: string;
  bucketId: string;
  /** Déconnexion après N minutes sans activité. */
  idleMinutes: number;
  /** Durée de session maximale côté navigateur, quelle que soit l'activité. */
  maxSessionHours: number;
}

/** Ligne de la table `readings` (écrite par le collecteur). */
export interface ReadingRow {
  $id: string;
  provider: string;
  energy: ReadingEnergy;
  granularity: 'month' | 'invoice' | 'day';
  periodStart: string;
  periodEnd: string;
  quantity: number;
  unit: 'kWh' | 'm3' | 'h';
  hp: number | null;
  hc: number | null;
  avgTemperature: number | null;
  $updatedAt: string;
}

/** Ligne de la table `invoices`. */
export interface InvoiceRow {
  $id: string;
  provider: string;
  energy: Energy;
  number: string;
  date: string;
  amount: number | null;
  amountDue: number | null;
  volume: number | null;
  fileId: string | null;
}

/** Ligne de la table `tariffs` : tarif TTC en vigueur à partir de `validFrom`. */
export interface TariffRow {
  $id: string;
  provider: string;
  energy: Energy;
  validFrom: string;
  offer: string | null;
  kwh: number | null;
  hp: number | null;
  hc: number | null;
  subscriptionMonthly: number | null;
}

export const ENERGY_LABEL: Record<Energy, string> = {
  electricity: 'Électricité',
  gas: 'Gaz',
  water: 'Eau',
};

export const PROVIDER_LABEL: Record<string, string> = {
  gdb: 'Gaz de Bordeaux',
  ileo: 'Iléo',
};
