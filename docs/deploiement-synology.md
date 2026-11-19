# Déployer sur un NAS Synology

Cible : DSM 7.2 ou plus, paquet **Container Manager**. Les images se construisent sur le NAS (Java, Node et nginx
existent en x86 comme en ARM, rien à choisir). Le premier build prend plusieurs minutes et ~1 Go de mémoire.

## Les couches de sécurité, de l'extérieur vers l'intérieur

| Couche | Ce qui est en place |
|---|---|
| Réseau | Seul le **reverse proxy DSM** (HTTPS, certificat Let's Encrypt) est joignable. Le dashboard n'écoute que sur `127.0.0.1:8095`. Le collecteur ne publie aucun port. |
| Isolation | Un réseau Docker par service : collecteur et dashboard ne se voient pas. |
| Conteneurs | Aucun ne tourne en root (collecteur : uid 10001, nginx : uid 101), **zéro capability** Linux, système de fichiers en lecture seule, mémoire / CPU / processus plafonnés, journaux limités (3 × 5 Mo). |
| Image | Bases Alpine allégées, versions épinglées au niveau mineur (Dependabot propose les mises à jour), aucun socket Docker monté, aucun secret dans l'image. |
| Navigateur | CSP stricte (seule l'origine Appwrite est autorisée), HSTS, pas d'iframe, pas de script en ligne. |
| Données | Droit de lecture limité à ton utilisateur (`user:<id>`), session limitée en durée et en inactivité, passkeys. |
| Secrets | Dans `.env` seulement (droits 600, hors git, hors image). Contrôlé par `scripts/preflight.sh`. |

## 1. Préparer Appwrite (console `appwrite.byj6n.fr`)

1. *Overview → Add platform → Web* : hostname du dashboard (ex. `conso.byj6n.fr`). Sans ça, CORS bloque le navigateur.
2. *Auth → Settings* : **fermer l'inscription libre**, régler la durée de session (par défaut 1 an : trop long ; 1 jour
   suffit), activer **Passkey** avec le domaine `byj6n.fr` (non modifiable ensuite).
3. *Auth → Users* : crée ton utilisateur et note son identifiant : `CONSO_APPWRITE_READ_ROLE=user:<id>`.
4. *Overview → API keys* : une clé avec uniquement les scopes listés dans `.env.example`.

## 2. Copier le projet sur le NAS

Dans *File Station*, crée un dossier réservé à l'administrateur, par exemple `/volume1/docker/conso` (clic droit →
Propriétés → Permission : retire tous les autres utilisateurs et groupes). Copie-y le dépôt (`git clone` en SSH, ou
dépose-le depuis ton PC). **Sur le NAS, le fichier `.env` EST la prod** : il contient les valeurs du projet Appwrite de
production, pas celles de ton instance de test.

```sh
cd /volume1/docker/conso
cp .env.example .env && chmod 600 .env     # puis remplis-le (éditeur de texte DSM ou nano)
sh scripts/preflight.sh                    # droits, valeurs obligatoires, https, droit de lecture, git
```

Le script ne fait aucun appel réseau et n'affiche aucun secret. Corrige tout ce qui commence par `✗`.

## 3. Démarrer

**Container Manager** → *Projet* → *Créer* → chemin `/volume1/docker/conso`, source « docker-compose.yml existant »
→ coche *Démarrer le projet*. Ou en SSH :

```sh
sudo docker compose up -d --build
sudo docker compose ps          # dashboard : "healthy" après ~30 s
sudo docker compose logs -f collector
```

Le collecteur lance une collecte au démarrage (`CONSO_RUN_ON_STARTUP=true`) puis tous les jours à 6 h 17.

## 4. Reverse proxy HTTPS (DSM)

*Panneau de configuration → Portail de connexion → Avancé → Proxy inversé → Créer* :

| Source | Destination |
|---|---|
| HTTPS · `conso.byj6n.fr` · port 443 | HTTP · `localhost` · port 8095 |

Puis *Panneau de configuration → Sécurité → Certificat* : ajoute un certificat **Let's Encrypt** pour
`conso.byj6n.fr` et associe-le à cette règle. Redirige le port 443 de ta box vers le NAS, **pas le 8095**.
Le dashboard envoie lui-même HSTS, CSP et les autres en-têtes : inutile de les doubler dans DSM.

Vérifie : `https://conso.byj6n.fr` s'ouvre, `http://<ip-du-nas>:8095` ne répond pas depuis un autre appareil du
réseau local.

## 5. Durcir DSM lui-même

- *Sécurité → Protection du compte* : double authentification sur les comptes administrateur, blocage automatique
  des IP après échecs (*Sécurité → Protection*), désactiver le compte `admin` par défaut.
- *Sécurité → Pare-feu* : n'autorise vers l'extérieur que 443 (et rien d'autre que tu n'utilises pas).
- Ne pas activer l'accès QuickConnect / UPnP pour ce service.
- SSH désactivé quand tu ne t'en sers pas.
- Garde DSM et Container Manager à jour (mises à jour automatiques de sécurité).

## 6. Exploitation

| Besoin | Commande |
|---|---|
| Mettre à jour l'appli | `git pull && sudo docker compose up -d --build` |
| Voir ce qui s'est passé | `sudo docker compose logs --tail 100 collector` |
| Forcer une collecte | `sudo docker compose restart collector` |
| Tout arrêter | `sudo docker compose down` (le volume du jeton Netatmo est conservé) |
| Faire tourner une clé / un mot de passe | modifier `.env`, puis `sudo docker compose up -d` (recrée le conteneur) |

**Sauvegardes** : *Hyper Backup* sur `/volume1/docker/conso` (contient `.env`, donc chiffre la sauvegarde) et sur le
volume Docker `conso_collector-data` (jeton Netatmo ; sinon, refais un refresh token et mets-le dans
`CONSO_NETATMO_REFRESH_TOKEN`). Les données elles-mêmes sont dans Appwrite : sauvegarde-le séparément.

**Test avant la prod** : sur ton PC, `.env` pointe sur le projet Appwrite de test. Ne copie jamais ce fichier sur le
NAS sans remplacer les identifiants.
