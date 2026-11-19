# Application installable et notifications de nouvelle facture

Le dashboard est une **PWA** : il s'installe sur l'écran d'accueil (Android et iPhone) et peut envoyer une
notification quand le collecteur trouve une nouvelle facture.

## Comment ça marche

```
Collecteur (6 h 17)                         Appareil (Android / iPhone)
 ├─ détecte une facture nouvelle              ├─ service worker (public/sw.js)
 ├─ lit les abonnements dans Appwrite  ◄──────┤ s'abonne depuis la page Profil
 ├─ chiffre le message (RFC 8291)             │  (ligne dans la table push_subscriptions,
 └─ l'envoie au service de push ─────────────►│   lisible par toi seul)
     (Google, Apple, Mozilla, Microsoft)       └─ affiche la notification
```

- Le message est **chiffré de bout en bout** : Google ou Apple transportent le message sans pouvoir le lire.
- L'appareil s'abonne lui-même ; l'abonnement est stocké dans Appwrite (table `push_subscriptions`), lisible
  uniquement par son propriétaire. Le collecteur le lit avec sa clé API.
- Le collecteur **n'envoie qu'aux services de push connus** (`fcm.googleapis.com`, `*.push.apple.com`,
  `*.push.services.mozilla.com`, `*.notify.windows.com`), en HTTPS : une ligne forgée ne peut pas lui faire appeler
  une adresse du réseau local.
- Pas de notification à la **première collecte** d'un fournisseur : tout l'historique n'est pas « nouveau ».
- Le service worker **ne met rien en cache** : les données personnelles ne restent pas dans le navigateur.

## Activer

1. Génère les clés une fois :
   ```sh
   node scripts/generate-vapid-keys.mjs
   # sans Node : docker run --rm -v "$PWD/scripts:/s:ro" node:26-alpine node /s/generate-vapid-keys.mjs
   ```
2. Colle les trois lignes dans le `.env` (prod), en remplaçant le contact par ta vraie adresse :
   `CONSO_VAPID_PUBLIC_KEY`, `CONSO_VAPID_PRIVATE_KEY` (secret), `CONSO_PUSH_SUBJECT=mailto:toi@exemple.fr`.
   Apple vérifie ce contact : il doit commencer par `mailto:` ou `https://`.
3. `sh scripts/preflight.sh`, puis redémarre : `docker compose up -d --build`.
   Au premier démarrage le collecteur crée la table `push_subscriptions`.
4. Sur chaque appareil : page **Profil → Notifications → Activer sur cet appareil**.

> Changer les clés VAPID plus tard invalide tous les abonnements : il faudra réactiver sur chaque appareil.

## Logo et icônes

Le logo source est `branding/logo.jpeg`. Le favicon et toutes les icônes (PWA, écran d'accueil iPhone, badge de
notification) en sont tirés par `python3 scripts/generate-icons.py` (Pillow requis) : les traits sont épaissis pour
rester lisibles en petit, et l'icône « maskable » garde le logo dans la zone que les launchers Android ne rognent pas.
Pour changer de logo : remplace `branding/logo.jpeg` et relance le script.

## Ce que font les notifications

- **Nouvelle facture** : « Nouvelle facture Iléo — eau · 295,87 € ». Au clic, la liste des factures s'ouvre sur
  cette facture, qui clignote un instant (plusieurs factures d'un coup : la liste complète).
- **Alerte de consommation** : quand un mois d'électricité ou de gaz dépasse le même mois de l'an dernier d'au moins
  `CONSO_ALERT_THRESHOLD_PCT` % (25 par défaut, 0 désactive). Une seule alerte par énergie et par collecte, pour le
  mois terminé le plus récent ; au clic, le graphique de cette énergie s'ouvre. Pas d'alerte à la première collecte
  d'un fournisseur, ni pour l'eau (relevés par facture, non comparables mois par mois).
- **Badge** sur l'icône de l'application installée : nombre de notifications non vues. Il repart à zéro quand tu
  ouvres l'application.
- **Appareils notifiés** (page Profil) : la liste des appareils abonnés, avec leur dernière activité, et la
  possibilité d'en retirer un (ancien téléphone, par exemple).
- **Nouvelle version** : si une version plus récente est déployée alors que l'application reste ouverte, un bandeau
  propose de recharger.
- **Raccourcis** : appui long sur l'icône de l'application (Android) pour aller à Factures, Graphique ou Profil.

## Proposition d'installation

À chaque connexion, si l'application n'est pas installée, un bandeau propose de l'installer : un bouton
« Installer » sur Android et ordinateur (Chrome, Edge), des instructions sur iPhone et iPad (Apple n'offre pas de
bouton). « Plus tard » le masque 14 jours. Il n'apparaît pas dans l'application installée, ni sur les navigateurs
qui ne savent pas installer (Safari et Firefox sur ordinateur).

## Android

Chrome, Edge, Firefox ou Samsung Internet : ouvre le dashboard, **Profil → Activer**, accepte la permission.
Aucune installation n'est requise. Pour l'icône sur l'écran d'accueil : menu du navigateur →
*Installer l'application* (ou *Ajouter à l'écran d'accueil*).

## iPhone et iPad

Apple n'autorise Web Push que pour une application **ajoutée à l'écran d'accueil** (**iOS / iPadOS 16.4 ou plus**) :

1. Ouvre le dashboard **dans Safari** (pas dans Chrome ou une autre appli).
2. Touche **Partager → Sur l'écran d'accueil → Ajouter**.
3. Ouvre **Conso** depuis l'écran d'accueil (pas depuis Safari).
4. **Profil → Notifications → Activer sur cet appareil**, puis *Autoriser*.

Dans Safari, sans installation, la page Profil affiche ces instructions au lieu du bouton.

## En cas de problème

| Symptôme | Piste |
|---|---|
| Pas de section « Notifications » dans Profil | `CONSO_VAPID_PUBLIC_KEY` absente côté dashboard : relance `docker compose up -d`. |
| « Autorisation refusée » / « bloquées » | Réglage du navigateur ou de l'appareil (Réglages → Notifications → Conso). |
| Rien n'arrive | `docker compose logs collector` : cherche `Notification de` ou `refusé`. Une notification n'est envoyée que pour une facture **nouvelle** et si le fournisseur avait déjà des factures. |
| Collecteur : « clés VAPID invalides » | Le collecteur refuse de démarrer : clés qui ne vont pas ensemble, ou `CONSO_PUSH_SUBJECT` mal formé. |
| Log « Abonnement … expiré : supprimé » | Normal : appareil désinstallé ou permission retirée, la ligne est nettoyée. |
