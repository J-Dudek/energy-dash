import { describe, expect, it } from 'vitest';
import { isPasskeyCancelled, passkeysSupported } from './passkey';

describe('passkey', () => {
  it("reconnaît l'annulation par l'utilisateur", () => {
    expect(isPasskeyCancelled(new DOMException('annulé', 'NotAllowedError'))).toBe(true);
    expect(isPasskeyCancelled(new DOMException('délai', 'AbortError'))).toBe(true);
  });

  it('ne prend pas les autres erreurs pour une annulation', () => {
    expect(isPasskeyCancelled(new DOMException('non', 'SecurityError'))).toBe(false);
    expect(isPasskeyCancelled(new Error('boom'))).toBe(false);
    expect(isPasskeyCancelled(null)).toBe(false);
  });

  it("masque les passkeys quand le navigateur n'a pas WebAuthn (jsdom)", () => {
    expect(passkeysSupported()).toBe(false);
  });
});
