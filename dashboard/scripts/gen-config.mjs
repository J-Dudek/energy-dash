// Dev local : génère public/config.json à partir du .env de la racine du repo.
// (En Docker, c'est docker-entrypoint.d/40-config-json.sh qui s'en charge.)
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// CONSO_ENV_FILE choisit le fichier (défaut .env = instance de test ; la prod est dans .env.prod).
const envName = process.env.CONSO_ENV_FILE || '.env';
const envPath = fileURLToPath(new URL(`../../${envName}`, import.meta.url));
const outPath = fileURLToPath(new URL('../public/config.json', import.meta.url));

if (!existsSync(envPath)) {
  console.warn(`[gen-config] ${envPath} introuvable : config.json inchangé.`);
  process.exit(0);
}

const env = {};
for (const line of readFileSync(envPath, 'utf8').split(/\r?\n/)) {
  const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
  if (m && !line.trimStart().startsWith('#')) env[m[1]] = m[2].replace(/^(['"])(.*)\1$/, '$2');
}

const endpoint = env.CONSO_APPWRITE_PUBLIC_ENDPOINT;
const projectId = env.CONSO_APPWRITE_PROJECT_ID;
if (!endpoint || !projectId) {
  console.error(
    '[gen-config] CONSO_APPWRITE_PUBLIC_ENDPOINT / CONSO_APPWRITE_PROJECT_ID manquants dans .env',
  );
  process.exit(1);
}

const config = {
  endpoint,
  projectId,
  databaseId: env.CONSO_APPWRITE_DATABASE_ID || 'conso',
  bucketId: env.CONSO_APPWRITE_BUCKET_ID || 'invoices',
  idleMinutes: Number(env.CONSO_SESSION_IDLE_MINUTES || 30),
  maxSessionHours: Number(env.CONSO_SESSION_MAX_HOURS || 12),
  vapidPublicKey: env.CONSO_VAPID_PUBLIC_KEY || '',
};
writeFileSync(outPath, JSON.stringify(config, null, 2) + '\n');
console.log('[gen-config] public/config.json généré depuis .env');
