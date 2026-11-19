/**
 * Déconnexion après inactivité (aucun clic, touche, défilement ni toucher). Les minuteurs sont ralentis quand
 * l'onglet est en arrière-plan ou l'appareil en veille : on revérifie aussi au retour sur l'onglet.
 * Garde-fou côté navigateur ; la durée de vie réelle de la session se règle dans la console Appwrite.
 */
export class IdleWatcher {
  private last: number;
  private timer?: number;
  private readonly events = ['pointerdown', 'keydown', 'scroll', 'touchstart'] as const;

  constructor(
    private readonly idleMs: number,
    private readonly onIdle: () => void,
    private readonly now: () => number = () => Date.now(),
  ) {
    this.last = now();
  }

  start(): void {
    this.stop();
    this.last = this.now();
    for (const e of this.events) window.addEventListener(e, this.touch, { passive: true });
    document.addEventListener('visibilitychange', this.check);
    this.timer = window.setInterval(this.check, 15_000);
  }

  stop(): void {
    for (const e of this.events) window.removeEventListener(e, this.touch);
    document.removeEventListener('visibilitychange', this.check);
    if (this.timer !== undefined) window.clearInterval(this.timer);
    this.timer = undefined;
  }

  /** Vrai si la limite d'inactivité est dépassée (exposé pour les tests). */
  isIdle(): boolean {
    return this.now() - this.last >= this.idleMs;
  }

  private readonly touch = (): void => {
    this.last = this.now();
  };

  private readonly check = (): void => {
    if (this.isIdle()) {
      this.stop();
      this.onIdle();
    }
  };
}
