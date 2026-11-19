#!/bin/sh
# Au démarrage du conteneur :
#   1. écrit la configuration publique du front (aucun secret : endpoint et ID de projet Appwrite) ;
#   2. génère les en-têtes de sécurité, dont la CSP qui n'autorise que l'origine Appwrite configurée.
set -eu

: "${APPWRITE_ENDPOINT:?APPWRITE_ENDPOINT manquant}"
: "${APPWRITE_PROJECT_ID:?APPWRITE_PROJECT_ID manquant}"

# Les valeurs finissent dans du JSON et dans un en-tête HTTP : on refuse tout ce qui n'a pas la forme attendue
# (guillemet, point-virgule, saut de ligne...) plutôt que de l'échapper.
case "$APPWRITE_ENDPOINT" in
  https://*|http://localhost*|http://127.0.0.1*) ;;
  *) echo "APPWRITE_ENDPOINT doit être en https (ou http://localhost pour tester)" >&2; exit 1 ;;
esac
if ! printf '%s' "$APPWRITE_ENDPOINT" | grep -Eq '^https?://[A-Za-z0-9._:-]+(/[A-Za-z0-9._/-]*)?$'; then
  echo "APPWRITE_ENDPOINT invalide (caractères inattendus)" >&2; exit 1
fi
if ! printf '%s' "$APPWRITE_PROJECT_ID" | grep -Eq '^[A-Za-z0-9._-]{1,64}$'; then
  echo "APPWRITE_PROJECT_ID invalide" >&2; exit 1
fi
DB="${APPWRITE_DATABASE_ID:-conso}"
BUCKET="${APPWRITE_BUCKET_ID:-invoices}"
for v in "$DB" "$BUCKET"; do
  printf '%s' "$v" | grep -Eq '^[A-Za-z0-9._-]{1,64}$' || { echo "identifiant Appwrite invalide" >&2; exit 1; }
done

# Durées de session côté navigateur (garde-fous : la durée réelle se règle dans la console Appwrite)
IDLE="${SESSION_IDLE_MINUTES:-30}"
MAXH="${SESSION_MAX_HOURS:-12}"
for v in "$IDLE" "$MAXH"; do
  printf '%s' "$v" | grep -Eq '^[0-9]{1,4}$' || { echo "durée de session invalide (entier attendu)" >&2; exit 1; }
done

# /tmp : le conteneur tourne avec un système de fichiers en lecture seule, /tmp est un tmpfs
mkdir -p /tmp/conso
cat > /tmp/conso/config.json <<JSON
{
  "endpoint": "${APPWRITE_ENDPOINT}",
  "projectId": "${APPWRITE_PROJECT_ID}",
  "databaseId": "${DB}",
  "bucketId": "${BUCKET}",
  "idleMinutes": ${IDLE},
  "maxSessionHours": ${MAXH}
}
JSON

ORIGIN="$(printf '%s' "$APPWRITE_ENDPOINT" | sed -E 's#^(https?://[^/]+).*#\1#')"

cat > /tmp/conso/security-headers.conf <<HEADERS
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' ${ORIGIN}; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "no-referrer" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()" always;
add_header Cross-Origin-Opener-Policy "same-origin" always;
add_header Cross-Origin-Resource-Policy "same-origin" always;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
HEADERS
