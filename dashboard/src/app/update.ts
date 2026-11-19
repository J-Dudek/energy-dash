import { Injectable, signal } from '@angular/core';

/** Nom du script principal (`main-XXXX.js`, haché à chaque build) dans une page HTML. */
export function mainScriptOf(html: string): string | null {
  const match = /<script[^>]+src="([^"]*\bmain[^"/]*\.js)"/.exec(html);
  return match?.[1] ?? null;
}

const CHECK_EVERY_MS = 30 * 60_000;
const MIN_GAP_MS = 5 * 60_000;

/**
 * Détecte qu'une nouvelle version est déployée : une application installée reste ouverte des jours sans se
 * recharger. On compare le script principal de la page chargée à celui que le serveur publie aujourd'hui
 * (le nom change à chaque build). Aucun cache, aucune dépendance au service worker.
 */
@Injectable({ providedIn: 'root' })
export class UpdateService {
  readonly available = signal(false);
  private started = false;
  private lastCheck = 0;

  start(): void {
    if (this.started) return;
    this.started = true;
    setInterval(() => void this.check(), CHECK_EVERY_MS);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') void this.check();
    });
  }

  async check(now: number = Date.now()): Promise<void> {
    if (this.available() || now - this.lastCheck < MIN_GAP_MS) return;
    this.lastCheck = now;
    try {
      const current = mainScriptOf(document.documentElement.outerHTML);
      const response = await fetch('/', { cache: 'no-store' });
      if (!response.ok) return;
      const published = mainScriptOf(await response.text());
      if (current && published && current !== published) this.available.set(true);
    } catch {
      // hors ligne ou serveur indisponible : on réessaiera
    }
  }

  reload(): void {
    window.location.reload();
  }
}
