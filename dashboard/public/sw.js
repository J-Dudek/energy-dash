/*
 * Service worker : reçoit les notifications Web Push, les affiche, tient le badge de l'icône et ouvre la bonne
 * page au clic. Il ne met RIEN en cache et n'intercepte aucune requête : les données (consommations, factures)
 * sont personnelles et ne doivent jamais traîner dans un cache du navigateur, et une page mise en cache serait une
 * page périmée après chaque déploiement.
 *
 * Le contenu d'une notification arrive chiffré de bout en bout : seul cet appareil peut le lire.
 */
self.addEventListener('install', () => {
  void self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

/**
 * Seules des pages de l'application sont acceptées (jamais une adresse arbitraire venue du message), avec leur
 * paramètre de requête que l'application valide à son tour.
 */
function safeUrl(value) {
  try {
    const url = new URL(typeof value === 'string' ? value : '/', self.location.origin);
    if (url.origin !== self.location.origin) return '/';
    if (url.pathname !== '/' && url.pathname !== '/profil') return '/';
    return url.pathname + url.search;
  } catch {
    return '/';
  }
}

/** Badge de l'icône = notifications encore affichées (donc pas encore vues). */
async function updateBadge() {
  if (!('setAppBadge' in self.navigator)) return;
  try {
    const count = (await self.registration.getNotifications()).length;
    if (count > 0) await self.navigator.setAppBadge(count);
    else await self.navigator.clearAppBadge();
  } catch {
    // badge non supporté ou refusé : sans importance
  }
}

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    // contenu illisible : on affiche quand même une notification générique (iOS l'exige pour chaque push)
  }
  const title = typeof data.title === 'string' && data.title ? data.title : 'Conso maison';
  const options = {
    body: typeof data.body === 'string' ? data.body : '',
    icon: 'icons/icon-192.png',
    badge: 'icons/badge-96.png',
    data: { url: safeUrl(data.url) },
  };
  // même tag = même notification (un renvoi ne l'affiche pas deux fois)
  if (typeof data.tag === 'string' && data.tag) options.tag = data.tag;
  event.waitUntil(self.registration.showNotification(title, options).then(() => updateBadge()));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = safeUrl(event.notification.data && event.notification.data.url);
  event.waitUntil(
    (async () => {
      await updateBadge();
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of windows) {
        if (new URL(client.url).origin === self.location.origin && 'focus' in client) {
          await client.focus();
          client.postMessage({ type: 'navigate', url }); // la page change de vue sans se recharger
          return;
        }
      }
      await self.clients.openWindow(url);
    })(),
  );
});
