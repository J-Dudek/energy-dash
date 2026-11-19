import { describe, expect, it } from 'vitest';
import { mainScriptOf } from './update';

describe('mainScriptOf', () => {
  it('repère le script principal haché', () => {
    const html =
      '<body><app-root></app-root><script src="chunk-ABC.js" type="module"></script><script src="main-HQVDQFA4.js" type="module"></script></body>';
    expect(mainScriptOf(html)).toBe('main-HQVDQFA4.js');
  });

  it('change quand une nouvelle version est publiée', () => {
    const before = '<script src="main-AAAA1111.js" type="module"></script>';
    const after = '<script src="main-BBBB2222.js" type="module"></script>';
    expect(mainScriptOf(before)).not.toBe(mainScriptOf(after));
  });

  it('en développement (main.js sans hachage) le nom ne change jamais', () => {
    expect(mainScriptOf('<script src="main.js" type="module"></script>')).toBe('main.js');
  });

  it("rend null quand il n'y a pas de script principal", () => {
    expect(mainScriptOf('<html><body>erreur 502</body></html>')).toBeNull();
  });
});
