import { describe, expect, it } from 'vitest';
import { DISMISS_DAYS, installMode, isDismissed } from './install';

describe('installMode', () => {
  const base = { isStandalone: false, isIos: false, hasPrompt: false };

  it("ne propose rien quand l'application est déjà installée", () => {
    expect(installMode({ ...base, isStandalone: true, hasPrompt: true })).toBe('installed');
    expect(installMode({ ...base, isStandalone: true, isIos: true })).toBe('installed');
  });

  it("propose l'installation d'un clic quand le navigateur la permet (Android)", () => {
    expect(installMode({ ...base, hasPrompt: true })).toBe('prompt');
  });

  it('donne des instructions sur iPhone / iPad (aucune API)', () => {
    expect(installMode({ ...base, isIos: true })).toBe('ios');
  });

  it('ne propose rien ailleurs (Safari ou Firefox sur ordinateur)', () => {
    expect(installMode(base)).toBe('none');
  });
});

describe('isDismissed', () => {
  const now = Date.UTC(2026, 10, 19);
  const day = 86_400_000;

  it('mémorise « Plus tard » pendant quelques jours', () => {
    expect(isDismissed(String(now - 2 * day), now)).toBe(true);
    expect(isDismissed(String(now - (DISMISS_DAYS - 1) * day), now)).toBe(true);
  });

  it('repropose passé le délai', () => {
    expect(isDismissed(String(now - (DISMISS_DAYS + 1) * day), now)).toBe(false);
  });

  it('repropose sans mémoire ou si la valeur est corrompue', () => {
    expect(isDismissed(null, now)).toBe(false);
    expect(isDismissed('pas-un-nombre', now)).toBe(false);
  });
});
