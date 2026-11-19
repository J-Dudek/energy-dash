import { afterEach, describe, expect, it, vi } from 'vitest';
import { IdleWatcher } from './idle-watcher';

describe('IdleWatcher', () => {
  let watcher: IdleWatcher | undefined;
  afterEach(() => watcher?.stop());

  function setup(idleMs = 60_000) {
    let now = 1_000_000;
    const onIdle = vi.fn();
    watcher = new IdleWatcher(idleMs, onIdle, () => now);
    watcher.start();
    return { onIdle, advance: (ms: number) => (now += ms) };
  }

  it('n\'est pas inactif juste après le démarrage', () => {
    setup();
    expect(watcher!.isIdle()).toBe(false);
  });

  it('devient inactif une fois la limite dépassée', () => {
    const { advance } = setup(60_000);
    advance(59_999);
    expect(watcher!.isIdle()).toBe(false);
    advance(1);
    expect(watcher!.isIdle()).toBe(true);
  });

  it('une activité repousse l\'échéance', () => {
    const { advance } = setup(60_000);
    advance(50_000);
    window.dispatchEvent(new Event('pointerdown'));
    advance(50_000);
    expect(watcher!.isIdle()).toBe(false);
  });

  it('déconnecte au retour sur un onglet resté inactif (minuteurs ralentis en arrière-plan)', () => {
    const { onIdle, advance } = setup(60_000);
    advance(120_000);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(onIdle).toHaveBeenCalledTimes(1);
  });

  it('ne rappelle pas onIdle une fois arrêté', () => {
    const { onIdle, advance } = setup(60_000);
    watcher!.stop();
    advance(120_000);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(onIdle).not.toHaveBeenCalled();
  });
});
