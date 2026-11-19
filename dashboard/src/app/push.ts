/**
 * Notifications Web Push côté navigateur : service worker, permission, abonnement. Marche sur Android (Chrome,
 * Firefox, Edge) et sur iPhone / iPad (iOS 16.4 ou plus) *uniquement si l'application est ajoutée à l'écran
 * d'accueil* : Safari n'expose pas Web Push dans un simple onglet.
 */

export type PushStatus =
  /** Le serveur n'a pas de clé VAPID : rien à proposer. */
  | 'unconfigured'
  /** iPhone / iPad dans Safari : il faut d'abord « Sur l'écran d'accueil ». */
  | 'ios-install'
  | 'unsupported'
  /** L'utilisateur a refusé : seul un réglage du navigateur ou de l'appareil peut le rétablir. */
  | 'denied'
  | 'available'
  | 'enabled';

export interface PushEnv {
  configured: boolean;
  isIos: boolean;
  isStandalone: boolean;
  hasServiceWorker: boolean;
  hasPushManager: boolean;
  hasNotification: boolean;
  permission: NotificationPermission | 'unsupported';
  subscribed: boolean;
}

/** Fonction pure (testable) : de ce que le navigateur sait faire à ce qu'on propose à l'utilisateur. */
export function pushStatus(env: PushEnv): PushStatus {
  if (!env.configured) return 'unconfigured';
  if (env.isIos && !env.isStandalone) return 'ios-install';
  if (!env.hasServiceWorker || !env.hasPushManager || !env.hasNotification) return 'unsupported';
  if (env.permission === 'denied') return 'denied';
  if (env.subscribed && env.permission === 'granted') return 'enabled';
  return 'available';
}

/** iPhone / iPad ? et l'application est-elle lancée depuis l'écran d'accueil (donc installée) ? */
export function detectPlatform(): { isIos: boolean; isStandalone: boolean } {
  const ua = navigator.userAgent;
  // iPadOS se présente comme un Mac : on le reconnaît à son écran tactile
  const isIos =
    /iPhone|iPad|iPod/.test(ua) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const nav = navigator as Navigator & { standalone?: boolean };
  return {
    isIos,
    isStandalone:
      window.matchMedia('(display-mode: standalone)').matches || nav.standalone === true,
  };
}

export function readPushEnv(configured: boolean, subscribed: boolean): PushEnv {
  const { isIos, isStandalone } = detectPlatform();
  return {
    configured,
    isIos,
    isStandalone,
    hasServiceWorker: 'serviceWorker' in navigator,
    hasPushManager: 'PushManager' in window,
    hasNotification: 'Notification' in window,
    permission: 'Notification' in window ? Notification.permission : 'unsupported',
    subscribed,
  };
}

/** Clé VAPID base64url → octets, comme l'attend `pushManager.subscribe`. */
export function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = value
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=');
  const binary = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** Identifiant stable d'un abonnement (32 caractères) : le même appareil retombe sur la même ligne. */
export async function subscriptionId(endpoint: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(endpoint));
  return [...new Uint8Array(digest)]
    .slice(0, 16)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/** Nom lisible de l'appareil pour la ligne d'abonnement, ex. « iPhone · 19/11/2026 ». */
export function deviceLabel(ua: string = navigator.userAgent, now: Date = new Date()): string {
  const kind = /iPhone|iPod/.test(ua)
    ? 'iPhone'
    : ua.includes('iPad')
      ? 'iPad'
      : ua.includes('Android')
        ? 'Android'
        : 'Ordinateur';
  return `${kind} · ${now.toLocaleDateString('fr-FR')}`;
}

/** À appeler au démarrage : sans service worker enregistré, ni notification ni installation. */
export async function registerServiceWorker(): Promise<void> {
  if (!('serviceWorker' in navigator)) return;
  try {
    await navigator.serviceWorker.register('sw.js');
  } catch {
    // contexte non sécurisé ou navigateur restrictif : l'application reste utilisable sans
  }
}

export async function currentSubscription(): Promise<PushSubscription | null> {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return null;
  const registration = await navigator.serviceWorker.ready;
  return registration.pushManager.getSubscription();
}

/** Demande la permission (à appeler depuis un clic) puis abonne l'appareil. */
export async function subscribe(vapidPublicKey: string): Promise<PushSubscription> {
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new PushDeniedError();
  const registration = await navigator.serviceWorker.ready;
  return registration.pushManager.subscribe({
    userVisibleOnly: true, // obligatoire : chaque message affiche une notification
    applicationServerKey: base64UrlToBytes(vapidPublicKey),
  });
}

export class PushDeniedError extends Error {
  constructor() {
    super('Notifications refusées');
  }
}

/** Adresse interne valide (chemin de l'application), jamais une adresse externe. */
export function isInternalUrl(value: unknown): value is string {
  return typeof value === 'string' && /^\/(?!\/)[A-Za-z0-9/?=&._%-]*$/.test(value);
}

/** Le service worker demande d'aller à une vue (clic sur une notification alors que l'application est ouverte). */
export function onServiceWorkerNavigate(handler: (url: string) => void): void {
  if (!('serviceWorker' in navigator)) return;
  navigator.serviceWorker.addEventListener('message', (event: MessageEvent<unknown>) => {
    const data = event.data as { type?: unknown; url?: unknown } | null;
    if (data?.type === 'navigate' && isInternalUrl(data.url)) handler(data.url);
  });
}

/** L'utilisateur a ouvert l'application : les notifications affichées sont vues, le badge repart à zéro. */
export async function markNotificationsSeen(): Promise<void> {
  try {
    if ('serviceWorker' in navigator) {
      const registration = await navigator.serviceWorker.getRegistration();
      for (const n of (await registration?.getNotifications()) ?? []) n.close();
    }
    if ('clearAppBadge' in navigator) await navigator.clearAppBadge();
  } catch {
    // non supporté : sans importance
  }
}
