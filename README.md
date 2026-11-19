# Conso maison

Dashboard des consommations d'eau (Iléo), de gaz et d'électricité (Gaz de Bordeaux), avec les
prix payés, le temps de chauffe de la chaudière (Netatmo, optionnel) et le téléchargement des
factures PDF. Auto-hébergé sur le NAS.

```
┌─────────────────────┐   1×/jour    ┌──────────────────────┐
│ collector           │ ───────────▶ │ espaceclient.gazde-  │
│ Spring Boot (Java)  │              │ bordeaux.fr (JSON)   │
│ @Scheduled          │ ───────────▶ │ www.mel-ileo.fr      │
│                     │              │ (HTML + PDF)         │
│                     │ ───────────▶ │ api.netatmo.com      │
└─────────┬───────────┘              │ (OAuth2, optionnel)  │
          │ clé API serveur          └──────────────────────┘
          ▼
┌─────────────────────┐   session    ┌──────────────────────┐
│ Appwrite (NAS)      │ ◀─────────── │ dashboard Angular    │
│ base "conso"        │   utilisateur│ (nginx, port 8095)   │
│ bucket "invoices"   │              └──────────────────────┘
└─────────────────────┘
```

- **collector** : se connecte aux espaces clients (et à Netatmo si activé), normalise les relevés,
  les factures et les tarifs, et pousse dans Appwrite. Les PDF absents du bucket sont téléchargés
  une seule fois. Les identifiants fournisseurs ne quittent pas ce conteneur.
- **Appwrite** : le collecteur crée tout au premier démarrage.
  - `readings` : un relevé par mois pour l'élec et le gaz, un par facture pour l'eau, un par jour
    pour le temps de chauffe (`heating`, en heures).
  - `invoices` : les factures (montant, reste dû, volume pour l'eau), avec leur PDF dans le bucket
    `invoices`.
  - `tariffs` : le tarif TTC du contrat (kWh, HP/HC, abonnement), un enregistrement par changement
    de prix, avec sa date d'effet.
- **dashboard** : connexion avec un compte Appwrite.
  - Cartes de synthèse : dernière période, évolution vs N-1, cumul 12 mois, **prix du kWh / du m³**,
    dernière facture en PDF. Sur mobile, elles défilent latéralement.
  - Graphique par énergie et vue tableau. Sur le gaz, la courbe du temps de chauffe (axe de
    droite, en heures) et le ratio kWh par heure de chauffe sur les mois complets.
  - Liste des factures, filtrable par fournisseur, avec le total.

## Ce que fournit chaque fournisseur

| | Gaz de Bordeaux | Iléo |
|---|---|---|
| Accès | API JSON interne de l'espace client (`/api/authentication-token`, token JWT 1 h) | Formulaire de connexion + pages HTML (session par cookie) |
| Relevés | Mensuels, 12-13 derniers mois ; élec en HP/HC, température moyenne | Volume par facture (≈ semestriel) ; le dashboard l'affiche en litres/jour |
| Factures | `/api/documents/{contrat}` + `POST /api/document/download` | Tableau « Mes factures » + `/common/pdfReader.aspx?cleDoc=…` |
| Tarifs | `/api/contracts/{contrat}` (`tarifScale` : kWh, HP/HC, abonnement) | Pas de tarif publié : le prix du m³ est calculé (montant ÷ volume de la dernière facture) |

Ce sont des API **non officielles**, relevées dans le navigateur : si un portail change, le
collecteur loguera une erreur pour ce fournisseur et continuera avec les autres. Les tarifs sont
secondaires : une erreur dessus est journalisée sans bloquer relevés et factures.

**Prix affichés** : TTC, abonnement exclu. Électricité : tarifs HP/HC du contrat pondérés par ta
répartition HP/HC des 12 derniers mois. Gaz : prix du kWh du contrat. Eau : prix du m³ tout compris
de la dernière facture.

### Netatmo (optionnel) : temps de chauffe

Contrairement aux deux autres, c'est l'**API officielle** Netatmo (OAuth2). Le collecteur lit le temps
de marche de la chaudière par jour (thermostat + relais, type de mesure `sum_boiler_on`, en
secondes) et le stocke comme relevés `heating` (en heures) dans `readings`, pour le rapprocher de la
consommation de gaz. Il relit 1000 jours d'historique à chaque collecte (maximum : Netatmo plafonne
à 1024 valeurs par appel). Sans thermostat avec relais, il n'y a pas de temps de chauffe.

Prérequis : une application sur dev.netatmo.com (client id/secret) et un jeton de renouvellement
avec le droit `read_thermostat` (voir `.env.example`). Netatmo change ce jeton à chaque usage : le
collecteur le garde dans `data/netatmo-token.json` (volume Docker `collector-data`, fichier
ignoré par git). Pour repartir d'un jeton neuf, remplace `CONSO_NETATMO_REFRESH_TOKEN` dans le
`.env` : il prend le pas sur le fichier.

L'historique se construit dans Appwrite au fil des collectes : Gaz de Bordeaux ne renvoie que
la dernière année glissante, mais les mois déjà collectés restent en base.

## Mise en place

1. **Appwrite** (console de ton instance)
   - Créer un projet (ou réutiliser un existant) et noter son ID.
   - *Overview → Platforms → Add platform → Web* : déclarer le nom d'hôte du dashboard
     (ex. `conso.byj6n.fr`, et `localhost` pour tester). Sans ça, le navigateur est bloqué par CORS.
   - *Overview → API keys* : créer une clé avec les scopes listés dans `.env.example`.
   - *Auth* : créer ton utilisateur (et celui de ta femme si besoin), puis désactiver
     l'inscription libre ou passer `CONSO_APPWRITE_READ_ROLE` à `team:<id>`.

2. **Configuration**
   ```bash
   cp .env.example .env
   # remplir identifiants Gaz de Bordeaux, Iléo, ID projet et clé API
   # (facultatif) activer Netatmo : voir la section ci-dessus
   chmod 600 .env
   ```
   **Deux environnements.** `.env` (défaut de tout : collecteur, dashboard, compose) doit pointer sur ton
   projet Appwrite **de test**. La prod vit dans `.env.prod`, qu'il faut choisir explicitement :
   `CONSO_ENV_FILE=.env.prod docker compose --env-file .env.prod up -d --build` (dev : `CONSO_ENV_FILE=.env.prod
   mvn spring-boot:run`, `CONSO_ENV_FILE=.env.prod npm start`). `.env.*` est ignoré par git.

3. **Lancement sur le NAS**
   ```bash
   docker compose up -d --build
   docker compose logs -f collector   # première collecte au démarrage
   ```
   Exemple de sortie : `[gdb] 25 relevés, 1 factures (1 nouveaux PDF), 2 tarifs`,
   `[ileo] 6 relevés, 6 factures (6 nouveaux PDF), 0 tarifs`, puis, si Netatmo est activé,
   `[netatmo] 612 jours de chauffe reçus (sum_boiler_on), max 3.9 h/jour`.

4. **Exposition** : reverse proxy DSM (*Portail de connexion → Proxy inversé*) de
   `conso.byj6n.fr` vers `localhost:8095`, avec le certificat Let's Encrypt. Le dashboard n'écoute
   que sur `127.0.0.1` : sans le proxy HTTPS, il n'est pas joignable depuis le réseau. Pour un test
   depuis le LAN, `CONSO_DASHBOARD_BIND=0.0.0.0` dans le `.env` (en HTTP, sans HTTPS : à éviter pour
   se connecter).

## Développement

```bash
# collecteur (Java 25, Spring Boot 4)
cd collector && mvn test && mvn spring-boot:run   # lit automatiquement le .env de la racine

# dashboard
cd dashboard && npm install && npm start          # génère public/config.json depuis le .env racine
cd dashboard && npx ng test --watch=false         # tests (vitest)
```

- **Collecteur** : Spring ne lit pas les `.env` nativement ; `application.yml` importe celui de la
  racine du repo (répertoire de travail = racine ou `collector/`). En Docker, c'est `env_file` de
  compose qui fournit les variables.
- **Dashboard** : le navigateur ne lit pas le `.env`. `npm start` lance d'abord
  `scripts/gen-config.mjs`, qui écrit `public/config.json` (endpoint public et ID de projet, aucun
  secret). Ce fichier est ignoré par git ; en Docker, le conteneur le régénère au démarrage.
  Avec `ng serve` directement, lance `npm run prestart` une fois avant.

### Qualité du code

Une seule commande par projet lance tout ; la CI GitHub (`.github/workflows/ci.yml`) fait la même chose.

| | Commande | Ce qu'elle vérifie |
|---|---|---|
| **Collecteur** | `cd collector && mvn verify` | Enforcer (Java ≥ 25, Maven ≥ 3.9), **Error Prone** à la compilation (avertissements bloquants), tests, **Spotless** (google-java-format, style AOSP), **SpotBugs + find-sec-bugs** (bugs et failles : injections, crypto, XXE...) |
| | `mvn spotless:apply` | Reformate le code Java |
| **Dashboard** | `cd dashboard && npm run check` | **ESLint** (typescript-eslint strict avec les types, angular-eslint, accessibilité des templates, `no-unsanitized` contre le XSS), **Stylelint** (SCSS), **Prettier**, tests, build de production |
| | `npm run lint:fix` · `npm run format` | Corrige le lint et le formatage |

- **TypeScript strict** : `strict`, `noUncheckedIndexedAccess` et `strictTemplates` sont activés.
- **`.mvn/jvm.config`** : ouvre les API internes du compilateur à Error Prone et à google-java-format
  (obligatoire avec les JDK récents). Le Dockerfile du collecteur le copie.
- **Hook de pré-commit** : vérifie les secrets (gitleaks, s'il est installé), le formatage Java et
  Angular sur les seuls fichiers commités. À activer une fois par clone :
  `git config core.hooksPath .githooks`.
- **CI** : collecteur, dashboard, Dockerfiles (Hadolint), scripts (ShellCheck), construction des images, et
  recherche de secrets dans tout l'historique (gitleaks). **CodeQL** analyse le Java et le TypeScript à chaque
  PR et chaque semaine ; **Dependabot** propose les mises à jour de dépendances.
- **EditorConfig** et `.gitattributes` : mêmes règles d'indentation et fins de ligne LF pour tous les éditeurs.

### Architecture du collecteur

Package `fr.byj6n.conso`, découpé par composant plutôt que par couche technique (c'est un batch :
pas de controller ni de JPA, la persistance passe par l'API REST d'Appwrite).

| Package | Rôle |
|---|---|
| `port` | `ProviderClient` (un fournisseur, sans état) et `ProviderSession` (une collecte : relevés, factures, tarifs, PDF) |
| `model` | Données normalisées : `Reading`, `Invoice`, `Tariff` |
| `gdb`, `ileo`, `netatmo` | Un client par fournisseur, avec son mapper ou parseur (fonctions pures, testées sur des exemples de JSON/HTML) |
| `appwrite` | `AppwriteSchema` (création idempotente de la base), `AppwriteStore` (upserts par identifiant déterministe), `AppwriteRows` (modèle → colonnes) |
| racine | `CollectorJob` (orchestration : relevés, factures, tarifs ; un fournisseur ou une étape en échec ne bloque pas le reste) |

Ajouter un fournisseur : implémenter `ProviderClient` et le déclarer dans `CollectorConfig`.

Les tests du dashboard (vitest) couvrent la validation de la configuration, le calcul des prix et de la
chauffe, l'inactivité et l'assainissement des noms de fichiers.

Les tests du collecteur couvrent le parsing (fixtures HTML Iléo, JSON Gaz de Bordeaux et Netatmo),
la gestion du jeton Netatmo, les appels REST Appwrite et l'orchestration de `CollectorJob`
(faux serveurs locaux et fournisseurs factices) ; ils ne touchent pas aux vrais portails.

## Sécurité

Des données personnelles (factures, adresse, consommations) transitent par ce projet. Ce qui est en
place, et ce qui reste à régler de ton côté.

### Ce que fait le code

**Session et navigateur**
- La session Appwrite vit dans un cookie `HttpOnly`, que le JavaScript de la page ne peut pas lire. Le
  SDK en garde parfois une copie dans `localStorage` (`cookieFallback`), lisible par n'importe quel script :
  le dashboard la **supprime dès que le cookie suffit** (à la connexion et au chargement) et ne la remet que
  si l'application ne fonctionne pas sans.
- Déconnexion après **30 min d'inactivité** (`CONSO_SESSION_IDLE_MINUTES`), et session limitée à
  **12 h** quelle que soit l'activité (`CONSO_SESSION_MAX_HOURS`). Les données affichées sont effacées de la
  mémoire de la page à chaque déconnexion. Une session refusée par le serveur (401) ramène à l'écran de
  connexion avec une explication.
- `config.json` est validé : l'endpoint Appwrite doit être en **https** (http seulement vers `localhost`),
  sinon l'application refuse de démarrer plutôt que d'envoyer le mot de passe en clair.
- Aucun `innerHTML`, aucune police ni script externe. Les noms de fichiers téléchargés sont assainis.

**Serveur web (nginx)**
- En-têtes : CSP stricte (scripts uniquement de l'origine du site, connexions uniquement vers l'origine
  Appwrite configurée), `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`,
  `Permissions-Policy`, COOP/CORP, HSTS. La CSP est générée au démarrage à partir de l'endpoint ; une valeur
  qui ne ressemble pas à une URL fait échouer le démarrage.
- Seules les méthodes GET et HEAD sont acceptées, les fichiers cachés renvoient 404, la version de nginx est masquée.

**Collecteur**
- Les identifiants et la clé API n'apparaissent pas dans les logs (`toString()` masqué).
- Il refuse de démarrer si `CONSO_APPWRITE_ENDPOINT` n'est pas en https (sauf `localhost` et le réseau local).
- Les **permissions** des tables et du bucket sont réappliquées à chaque démarrage : resserrer
  `CONSO_APPWRITE_READ_ROLE` s'applique aussi aux tables déjà créées.
- Pas de redirection suivie avec le mot de passe (Gaz de Bordeaux), redirections limitées à `mel-ileo.fr` (Iléo),
  PDF limités à 5 Mo, dates des fournisseurs validées strictement, jeton Netatmo dans un fichier `600`.

**Conteneurs** : système de fichiers en lecture seule, aucune capability Linux superflue, utilisateur non-root
pour le collecteur, installation npm sans exécution de scripts. Un avertissement est écrit au démarrage si la
lecture des données est ouverte à `users`.

### À régler dans la console Appwrite

Le cookie de session et sa durée sont gérés par Appwrite : les garde-fous du navigateur ci-dessus ne
remplacent pas ces réglages (un minuteur côté navigateur n'est pas une frontière de sécurité).

1. **Inscription libre** : *Auth → Security* : limiter le nombre d'utilisateurs à ceux qui existent (le
   libellé varie selon la version). Sinon, avec `CONSO_APPWRITE_READ_ROLE=users`, toute personne qui crée un
   compte peut lire tes factures. Mieux : `CONSO_APPWRITE_READ_ROLE=team:<id>` ou `user:<id>`.
2. **Durée de session** : *Auth → Security → Session duration*. Appwrite propose **un an** par défaut ; passe
   à quelques jours (par exemple 7) ou moins.
3. **Sessions simultanées** : limiter le maximum de sessions par utilisateur (3 par exemple).
4. **Mots de passe** : activer le dictionnaire de mots de passe courants, l'historique et le contrôle des
   données personnelles, avec une longueur minimale de 12.
5. **Méthodes de connexion** : ne garder que e-mail + mot de passe (désactiver anonyme, lien magique, OAuth
   inutilisés).
6. **Clé API du collecteur** : uniquement les scopes de `.env.example`, sans date d'expiration lointaine.
7. **Domaine** : après une connexion, dans la console du navigateur, `localStorage.getItem('cookieFallback')`
   doit renvoyer `null`. Si ce n'est pas le cas, l'API et le dashboard ne partagent pas un domaine de confiance :
   héberge Appwrite sur un sous-domaine du même domaine que le dashboard, et déclare-le comme domaine
   personnalisé du projet.

### À garder en tête

- Le `.env` contient les mots de passe de tes comptes fournisseurs : `chmod 600`, jamais dans une sauvegarde
  partagée. Le volume `collector-data` contient le jeton Netatmo.
- Gaz de Bordeaux et Iléo n'ont pas d'API officielle : les identifiants sont ceux de ton compte client, donne-leur
  un mot de passe unique.
- Le dashboard gère les **passkeys** (WebAuthn) : bouton « Se connecter avec une passkey » et gestion dans le
  page Profil (icône ronde en haut à droite, qui permet aussi de changer l'adresse e-mail). Il faut les activer dans la console Appwrite (Auth > Settings > Passkey) avec
  le domaine de confiance (ex. `byj6n.fr`), **non modifiable** dès qu'une passkey existe. Le mot de passe reste
  utilisable ; il n'y a pas encore de TOTP sur l'écran de connexion.
- Les styles sont autorisés en ligne (`style-src 'unsafe-inline'`), car Angular en injecte ; les scripts, eux, ne le sont pas.

## Application installable et notifications

Le dashboard est une PWA (icône sur l'écran d'accueil Android et iPhone) et notifie l'arrivée d'une nouvelle
facture par Web Push. Guide : [docs/notifications.md](docs/notifications.md) (génération des clés VAPID,
installation sur iPhone, dépannage).

## Déploiement sur un NAS Synology

Guide pas à pas (Container Manager, reverse proxy HTTPS, durcissement DSM, sauvegardes) :
[docs/deploiement-synology.md](docs/deploiement-synology.md). Avant un déploiement, `sh scripts/preflight.sh`
vérifie le `.env`.

## Pistes

- Suivi journalier de l'électricité (Linky) ou du gaz (Gazpar) : non vérifié, l'accès des
  particuliers aux API Enedis et GRDF est à confirmer.
- TOTP Appwrite sur l'écran de connexion (les passkeys sont déjà gérées).
- Alerte (mail via ton infra j6n.fr) quand une facture est disponible ou qu'un mois dépasse N-1.
