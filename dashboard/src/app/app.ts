import type { OnDestroy, OnInit } from '@angular/core';
import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterOutlet } from '@angular/router';
import { AppwriteService } from './appwrite.service';
import { errorMessage } from './errors';
import { IdleWatcher } from './idle-watcher';
import { isPasskeyCancelled, passkeysSupported } from './passkey';
import { DashboardStore } from './dashboard-store';
import { InstallService } from './install';
import { UpdateService } from './update';
import { markNotificationsSeen, onServiceWorkerNavigate, registerServiceWorker } from './push';

/** Coquille : chargement, écran de connexion, surveillance d'inactivité ; les pages sont dans le routeur. */
@Component({
  selector: 'app-root',
  imports: [FormsModule, RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit, OnDestroy {
  protected readonly aw = inject(AppwriteService);
  private readonly router = inject(Router);
  private readonly install = inject(InstallService);
  protected readonly update = inject(UpdateService);
  private readonly store = inject(DashboardStore);
  private idle?: IdleWatcher;

  protected readonly ready = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly passkeySupported = passkeysSupported();

  private wasSignedIn = false;

  protected email = '';
  protected password = '';

  constructor() {
    // Connecté : on surveille l'inactivité. Déconnecté (par nous, par expiration ou par le serveur) : retour à
    // l'accueil et plus aucune donnée personnelle en mémoire (les pages sont détruites avec le routeur).
    effect(() => {
      if (this.aw.user()) {
        this.idle ??= new IdleWatcher(this.aw.idleMs, () => {
          void this.aw.expire(`Déconnecté après ${this.aw.idleMs / 60_000} min d'inactivité.`);
        });
        this.idle.start();
        this.wasSignedIn = true;
        void this.aw.syncPushSubscription();
        void markNotificationsSeen();
      } else {
        this.idle?.stop();
        this.email = '';
        this.password = '';
        // Retour à l'accueil seulement si on vient d'être déconnecté : une notification ouvre /?facture=… alors
        // qu'on n'est pas encore connecté, et ce lien doit survivre à l'écran de connexion.
        if (this.wasSignedIn) void this.router.navigateByUrl('/');
        this.wasSignedIn = false;
      }
    });
  }

  ngOnInit(): void {
    void this.initialize();
  }

  ngOnDestroy(): void {
    this.idle?.stop();
  }

  private async initialize(): Promise<void> {
    void registerServiceWorker();
    this.update.start();
    onServiceWorkerNavigate((url) => {
      if (this.aw.user()) void this.router.navigateByUrl(url);
    });
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && this.aw.user()) {
        void markNotificationsSeen();
        void this.store.refreshIfStale();
      }
    });
    try {
      await this.aw.init();
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.ready.set(true);
    }
  }

  protected async login(): Promise<void> {
    await this.signIn(async () => {
      await this.aw.login(this.email, this.password);
      this.password = '';
    });
  }

  protected async loginWithPasskey(): Promise<void> {
    await this.signIn(() => this.aw.loginWithPasskey());
  }

  private async signIn(action: () => Promise<void>): Promise<void> {
    this.error.set(null);
    this.loading.set(true);
    try {
      await action();
      this.install.offerAfterLogin();
    } catch (e) {
      this.error.set(isPasskeyCancelled(e) ? 'Connexion par passkey annulée.' : errorMessage(e));
    } finally {
      this.loading.set(false);
    }
  }
}
