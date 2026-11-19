// Génère une paire de clés VAPID (P-256) pour les notifications Web Push.
// Usage : node scripts/generate-vapid-keys.mjs
// Sans Node installé : docker run --rm -v "$PWD/scripts:/s:ro" node:26-alpine node /s/generate-vapid-keys.mjs
import { generateKeyPairSync } from 'node:crypto';

const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const jwk = privateKey.export({ format: 'jwk' });

// Clé publique : point non compressé 0x04 || X || Y (65 octets) ; clé privée : scalaire D (32 octets)
const b64url = (b64) => b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const raw = (jwkValue) => Buffer.from(jwkValue, 'base64url');
const publicKey = Buffer.concat([Buffer.from([0x04]), raw(jwk.x), raw(jwk.y)]).toString('base64url');

console.log('# À ajouter au .env (la clé privée est un secret : jamais dans git, ni dans un message).');
console.log(`CONSO_VAPID_PUBLIC_KEY=${publicKey}`);
console.log(`CONSO_VAPID_PRIVATE_KEY=${b64url(jwk.d)}`);
console.log('CONSO_PUSH_SUBJECT=mailto:remplace-par-ton-adresse@exemple.fr');
