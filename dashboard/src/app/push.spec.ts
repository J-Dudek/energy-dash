import { describe, expect, it } from 'vitest';
import type { PushEnv } from './push';
import { base64UrlToBytes, deviceLabel, pushStatus, subscriptionId } from './push';

const ok: PushEnv = {
  configured: true,
  isIos: false,
  isStandalone: false,
  hasServiceWorker: true,
  hasPushManager: true,
  hasNotification: true,
  permission: 'default',
  subscribed: false,
};

describe('pushStatus', () => {
  it('propose l’activation quand tout est prêt', () => {
    expect(pushStatus(ok)).toBe('available');
  });

  it('ne propose rien sans clé VAPID côté serveur', () => {
    expect(pushStatus({ ...ok, configured: false })).toBe('unconfigured');
  });

  it("demande d'installer l'application sur iPhone / iPad dans Safari", () => {
    expect(pushStatus({ ...ok, isIos: true, isStandalone: false, hasPushManager: false })).toBe(
      'ios-install',
    );
  });

  it("autorise les notifications sur iPhone une fois l'application installée", () => {
    expect(pushStatus({ ...ok, isIos: true, isStandalone: true })).toBe('available');
  });

  it('signale un navigateur sans Web Push', () => {
    expect(pushStatus({ ...ok, hasPushManager: false })).toBe('unsupported');
    expect(pushStatus({ ...ok, hasServiceWorker: false })).toBe('unsupported');
    expect(pushStatus({ ...ok, hasNotification: false, permission: 'unsupported' })).toBe(
      'unsupported',
    );
  });

  it('distingue refus et activation', () => {
    expect(pushStatus({ ...ok, permission: 'denied' })).toBe('denied');
    expect(pushStatus({ ...ok, permission: 'granted', subscribed: true })).toBe('enabled');
    expect(pushStatus({ ...ok, permission: 'granted', subscribed: false })).toBe('available');
  });
});

describe('base64UrlToBytes', () => {
  it('décode la clé VAPID (65 octets, point non compressé)', () => {
    const key =
      'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8';
    const bytes = base64UrlToBytes(key);
    expect(bytes).toHaveLength(65);
    expect(bytes[0]).toBe(4);
  });
});

describe('subscriptionId', () => {
  it('est stable, de 32 caractères, et différent pour un autre appareil', async () => {
    const a = await subscriptionId('https://fcm.googleapis.com/fcm/send/aaa');
    expect(a).toHaveLength(32);
    expect(a).toMatch(/^[0-9a-f]{32}$/);
    expect(await subscriptionId('https://fcm.googleapis.com/fcm/send/aaa')).toBe(a);
    expect(await subscriptionId('https://fcm.googleapis.com/fcm/send/bbb')).not.toBe(a);
  });
});

describe('deviceLabel', () => {
  const day = new Date('2026-11-19T10:00:00Z');
  it.each([
    ['Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)', 'iPhone'],
    ['Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X)', 'iPad'],
    ['Mozilla/5.0 (Linux; Android 14; Pixel 8)', 'Android'],
    ['Mozilla/5.0 (X11; Linux x86_64)', 'Ordinateur'],
  ])('%s', (ua, kind) => {
    expect(deviceLabel(ua, day)).toBe(`${kind} · 19/11/2026`);
  });
});
