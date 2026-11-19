#!/bin/sh
# Vérifie la configuration avant un déploiement (aucun appel réseau, aucun secret affiché).
# Usage : sh scripts/preflight.sh [fichier-env]     (défaut : .env, ou $CONSO_ENV_FILE)
set -eu

ENV_FILE="${1:-${CONSO_ENV_FILE:-.env}}"
fail=0
bad() { echo "✗ $1" >&2; fail=1; }
ok() { echo "✓ $1"; }

[ -f "$ENV_FILE" ] || { echo "✗ $ENV_FILE introuvable (copie .env.example et remplis-le)" >&2; exit 1; }

# Valeur d'une variable du fichier (dernière occurrence, guillemets retirés)
get() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$ENV_FILE" | tail -n 1 | sed -E "s/[[:space:]]+$//; s/^(['\"])(.*)\1$/\2/"
}

# 1. Droits du fichier : il contient des mots de passe
perm="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE")"
case "$perm" in
  600|400) ok "droits de $ENV_FILE : $perm" ;;
  *) bad "droits de $ENV_FILE : $perm (attendu 600 : chmod 600 $ENV_FILE)" ;;
esac

# 2. Variables obligatoires
for v in CONSO_APPWRITE_ENDPOINT CONSO_APPWRITE_PUBLIC_ENDPOINT CONSO_APPWRITE_PROJECT_ID CONSO_APPWRITE_API_KEY CONSO_APPWRITE_READ_ROLE; do
  if [ -n "$(get "$v")" ]; then ok "$v renseignée"; else bad "$v vide ou absente"; fi
done

# 3. L'endpoint vu par le navigateur doit être en https (la clé API, elle, ne quitte jamais le collecteur)
case "$(get CONSO_APPWRITE_PUBLIC_ENDPOINT)" in
  https://*) ok "endpoint public en https" ;;
  *) bad "CONSO_APPWRITE_PUBLIC_ENDPOINT doit commencer par https://" ;;
esac
case "$(get CONSO_APPWRITE_ENDPOINT)" in
  https://*) ok "endpoint du collecteur en https" ;;
  http://*) echo "! CONSO_APPWRITE_ENDPOINT en http : acceptable sur un réseau local de confiance seulement" ;;
  *) bad "CONSO_APPWRITE_ENDPOINT invalide" ;;
esac

# 4. Droit de lecture : ni la valeur d'exemple, ni « users » sans y avoir réfléchi
role="$(get CONSO_APPWRITE_READ_ROLE)"
case "$role" in
  *REMPLACER*) bad "CONSO_APPWRITE_READ_ROLE contient encore la valeur d'exemple" ;;
  users) echo "! CONSO_APPWRITE_READ_ROLE=users : tout compte du projet lit tes données (ferme l'inscription libre)" ;;
  user:*|team:*) ok "droit de lecture limité ($role)" ;;
  *) bad "CONSO_APPWRITE_READ_ROLE inattendu" ;;
esac

# 5. Exposition du dashboard : jamais sur toutes les interfaces sans le vouloir
case "$(get CONSO_DASHBOARD_BIND)" in
  ""|127.0.0.1) ok "dashboard à l'écoute en local seulement (reverse proxy requis)" ;;
  *) echo "! CONSO_DASHBOARD_BIND expose le dashboard hors de la machine, en HTTP : à réserver à un essai" ;;
esac

# 6. Le .env ne doit pas être suivi par git
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  if git ls-files --error-unmatch "$ENV_FILE" >/dev/null 2>&1; then
    bad "$ENV_FILE est suivi par git (git rm --cached $ENV_FILE)"
  else
    ok "$ENV_FILE n'est pas suivi par git"
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "Corrige les points ci-dessus avant de déployer." >&2
  exit 1
fi
echo "Prêt."
