import { Injectable, computed, signal } from '@angular/core';
import { detectPlatform } from './push';

/** Événement non standard (Chrome, Edge, Samsung Internet sur Android et ordinateur) : l'installation à la demande. */
export interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export type InstallMode =
  /** Déjà lancée depuis l'écran d'accueil : rien à proposer. */
  | 'installed'
  /** Le navigateur sait installer d'un clic (Android, Chrome / Edge). */
  | 'prompt'
  /** iPhone / iPad : aucune API, seulement des instructions (Partager → Sur l'écran d'accueil). */
  | 'ios'
  /** Safari / Firefox sur ordinateur, ou événement pas (encore) reçu. */
  | 'none';

export const DISMISS_DAYS = 14;
const DISMISS_KEY = 'conso.installDismissedAt';

export function installMode(env: {
  isStandalone: boolean;
  isIos: boolean;
  hasPrompt: boolean;
}): InstallMode {
  if (env.isStandalone) return 'installed';
  if (env.hasPrompt) return 'prompt';
  if (env.isIos) return 'ios';
  return 'none';
}

/** « Plus tard » est mémorisé quelques jours : on propose à la connexion, sans harceler. */
export function isDismissed(raw: string | null, now: number): boolean {
  const at = Number(raw);
  return raw !== null && Number.isFinite(at) && now - at < DISMISS_DAYS * 86_400_000;
}

/**
 * Proposition d'installer l'application à la connexion. L'événement `beforeinstallprompt` peut arriver avant
 * ou après la connexion : on le garde et la bannière s'affiche dès que les deux conditions sont réunies.
 */
@Injectable({ providedIn: 'root' })
export class InstallService {
  private readonly deferred = signal<BeforeInstallPromptEvent | null>(null);
  private readonly installed = signal(detectPlatform().isStandalone);
  private readonly offered = signal(false);

  readonly mode = computed<InstallMode>(() =>
    installMode({
      isStandalone: this.installed(),
      isIos: detectPlatform().isIos,
      hasPrompt: this.deferred() !== null,
    }),
  );
  /** La bannière : connexion faite, pas installée, pas écartée récemment, et une façon d'installer à montrer. */
  readonly visible = computed(
    () => this.offered() && (this.mode() === 'prompt' || this.mode() === 'ios'),
  );

  constructor() {
    window.addEventListener('beforeinstallprompt', (event) => {
      event.preventDefault(); // on affiche notre propre bannière, au bon moment
      this.deferred.set(event as BeforeInstallPromptEvent);
    });
    window.addEventListener('appinstalled', () => {
      this.installed.set(true);
      this.deferred.set(null);
      this.offered.set(false);
    });
  }

  /** À appeler après une connexion (pas après un simple rechargement de la page). */
  offerAfterLogin(): void {
    if (this.mode() === 'installed' || this.recentlyDismissed()) return;
    this.offered.set(true);
  }

  async install(): Promise<void> {
    const event = this.deferred();
    if (!event) return;
    this.deferred.set(null); // un événement ne sert qu'une fois
    await event.prompt();
    const choice = await event.userChoice;
    this.offered.set(false);
    if (choice.outcome === 'dismissed') this.remember();
  }

  dismiss(): void {
    this.offered.set(false);
    this.remember();
  }

  private recentlyDismissed(): boolean {
    try {
      return isDismissed(localStorage.getItem(DISMISS_KEY), Date.now());
    } catch {
      return false; // stockage indisponible : on propose
    }
  }

  private remember(): void {
    try {
      localStorage.setItem(DISMISS_KEY, String(Date.now()));
    } catch {
      // stockage indisponible : la bannière réapparaîtra à la prochaine connexion
    }
  }
}
