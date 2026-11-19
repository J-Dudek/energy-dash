import type { AppConfig } from './models';

const ID = /^[A-Za-z0-9._-]{1,64}$/;
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

export const DEFAULT_IDLE_MINUTES = 30;
export const DEFAULT_MAX_SESSION_HOURS = 12;

/**
 * Valide /config.json avant de s'en servir. L'endpoint reçoit l'e-mail et le mot de passe : il doit être en
 * https (http seulement vers la machine locale), sans quoi on refuse de démarrer plutôt que d'envoyer des
 * identifiants en clair.
 */
export function validateConfig(raw: unknown): AppConfig {
  if (typeof raw !== 'object' || raw === null)
    throw new Error('Configuration invalide (config.json)');
  const c = raw as Record<string, unknown>;

  const endpoint = typeof c['endpoint'] === 'string' ? c['endpoint'] : '';
  let url: URL;
  try {
    url = new URL(endpoint);
  } catch {
    throw new Error('Configuration invalide : endpoint Appwrite illisible');
  }
  const secure =
    url.protocol === 'https:' || (url.protocol === 'http:' && LOCAL_HOSTS.has(url.hostname));
  if (!secure) {
    throw new Error(
      "Configuration refusée : l'endpoint Appwrite doit être en https (identifiants en clair sinon)",
    );
  }
  for (const key of ['projectId', 'databaseId', 'bucketId']) {
    if (typeof c[key] !== 'string' || !ID.test(c[key])) {
      throw new Error(`Configuration invalide : ${key}`);
    }
  }
  return {
    endpoint: endpoint.replace(/\/+$/, ''),
    projectId: c['projectId'] as string,
    databaseId: c['databaseId'] as string,
    bucketId: c['bucketId'] as string,
    idleMinutes: bounded(c['idleMinutes'], 1, 24 * 60, DEFAULT_IDLE_MINUTES),
    maxSessionHours: bounded(c['maxSessionHours'], 1, 24 * 30, DEFAULT_MAX_SESSION_HOURS),
  };
}

function bounded(value: unknown, min: number, max: number, fallback: number): number {
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) && n >= min && n <= max ? n : fallback;
}
