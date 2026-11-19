import { describe, expect, it } from 'vitest';
import { DEFAULT_IDLE_MINUTES, DEFAULT_MAX_SESSION_HOURS, validateConfig } from './config';

const base = {
  endpoint: 'https://appwrite.byj6n.fr/v1',
  projectId: 'abc123',
  databaseId: 'conso',
  bucketId: 'invoices',
};

describe('validateConfig', () => {
  it('accepte une configuration https et applique les durées par défaut', () => {
    const c = validateConfig(base);
    expect(c.endpoint).toBe('https://appwrite.byj6n.fr/v1');
    expect(c.idleMinutes).toBe(DEFAULT_IDLE_MINUTES);
    expect(c.maxSessionHours).toBe(DEFAULT_MAX_SESSION_HOURS);
  });

  it("retire les / finaux de l'endpoint", () => {
    expect(validateConfig({ ...base, endpoint: 'https://a.fr/v1///' }).endpoint).toBe(
      'https://a.fr/v1',
    );
  });

  it.each(['http://localhost:9999/v1', 'http://127.0.0.1/v1'])(
    'accepte http vers la machine locale (%s)',
    (endpoint) => {
      expect(validateConfig({ ...base, endpoint }).endpoint).toBe(endpoint);
    },
  );

  it.each([
    'http://appwrite.byj6n.fr/v1',
    'http://192.168.1.10/v1',
    'ftp://a.fr',
    'javascript:alert(1)',
    'pas une url',
    '',
  ])('refuse un endpoint non sécurisé ou illisible (%s)', (endpoint) => {
    expect(() => validateConfig({ ...base, endpoint })).toThrow();
  });

  it.each(['projectId', 'databaseId', 'bucketId'])('refuse un %s hors du format attendu', (key) => {
    expect(() => validateConfig({ ...base, [key]: 'a"; b' })).toThrow(/invalide/);
    expect(() => validateConfig({ ...base, [key]: '' })).toThrow(/invalide/);
    expect(() => validateConfig({ ...base, [key]: 12 })).toThrow(/invalide/);
  });

  it("refuse ce qui n'est pas un objet", () => {
    expect(() => validateConfig(null)).toThrow();
    expect(() => validateConfig('x')).toThrow();
  });

  it('borne les durées de session et retombe sur les valeurs par défaut', () => {
    expect(validateConfig({ ...base, idleMinutes: 5, maxSessionHours: 2 })).toMatchObject({
      idleMinutes: 5,
      maxSessionHours: 2,
    });
    expect(validateConfig({ ...base, idleMinutes: 0 }).idleMinutes).toBe(DEFAULT_IDLE_MINUTES);
    expect(validateConfig({ ...base, idleMinutes: 99999 }).idleMinutes).toBe(DEFAULT_IDLE_MINUTES);
    expect(validateConfig({ ...base, maxSessionHours: 'abc' }).maxSessionHours).toBe(
      DEFAULT_MAX_SESSION_HOURS,
    );
    expect(validateConfig({ ...base, idleMinutes: '15' }).idleMinutes).toBe(15);
  });
});
