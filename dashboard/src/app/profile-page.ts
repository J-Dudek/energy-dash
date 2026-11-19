import type { OnInit } from '@angular/core';
import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import type { Models } from 'appwrite';
import { AppwriteException } from 'appwrite';
import { AppwriteService } from './appwrite.service';
import { BrandLink } from './brand-link';
import { defaultPasskeyName, isPasskeyCancelled, passkeysSupported } from './passkey';
import type { PushDevice } from './models';
import type { PushStatus } from './push';
import {
  PushDeniedError,
  currentSubscription,
  pushStatus,
  readPushEnv,
  subscribe,
  subscriptionId,
} from './push';

/** Page de profil : changement d'adresse e-mail, passkeys, déconnexion. */
@Component({
  selector: 'app-profile-page',
  imports: [FormsModule, DatePipe, RouterLink, BrandLink],
  templateUrl: './profile-page.html',
  styleUrl: './profile-page.scss',
})
export class ProfilePage implements OnInit {
  protected readonly aw = inject(AppwriteService);

  protected readonly passkeySupported = passkeysSupported();
  protected readonly passkeys = signal<Models.Passkey[]>([]);
  protected readonly busy = signal(false);
  protected readonly emailMessage = signal<string | null>(null);
  protected readonly passkeyMessage = signal<string | null>(null);

  protected readonly pushState = signal<PushStatus>('unconfigured');
  protected readonly pushMessage = signal<string | null>(null);
  protected readonly devices = signal<PushDevice[]>([]);
  /** Identifiant de l'abonnement de CET appareil, pour le repérer dans la liste. */
  protected readonly thisDeviceId = signal<string | null>(null);

  protected newEmail = '';
  protected password = '';

  ngOnInit(): void {
    this.newEmail = this.aw.user()?.email ?? '';
    if (this.passkeySupported) void this.refreshPasskeys();
    void this.refreshPush();
  }

  protected async enablePush(): Promise<void> {
    const key = this.aw.vapidPublicKey;
    if (!key) return;
    this.busy.set(true);
    this.pushMessage.set(null);
    try {
      const subscription = await subscribe(key);
      await this.aw.savePushSubscription(subscription);
      this.pushMessage.set('Notifications activées sur cet appareil.');
    } catch (e) {
      this.pushMessage.set(
        e instanceof PushDeniedError ? 'Autorisation refusée.' : this.message(e),
      );
    } finally {
      await this.refreshPush();
      this.busy.set(false);
    }
  }

  protected async disablePush(): Promise<void> {
    this.busy.set(true);
    this.pushMessage.set(null);
    try {
      const subscription = await currentSubscription();
      if (subscription) {
        await this.aw.deletePushSubscription(subscription.endpoint);
        await subscription.unsubscribe();
      }
      this.pushMessage.set('Notifications désactivées sur cet appareil.');
    } catch (e) {
      this.pushMessage.set(this.message(e));
    } finally {
      await this.refreshPush();
      this.busy.set(false);
    }
  }

  protected async removeDevice(device: PushDevice): Promise<void> {
    this.busy.set(true);
    this.pushMessage.set(null);
    try {
      await this.aw.deletePushDevice(device.id);
      if (device.id === this.thisDeviceId()) await (await currentSubscription())?.unsubscribe();
      this.pushMessage.set('Appareil retiré.');
    } catch (e) {
      this.pushMessage.set(this.message(e));
    } finally {
      await this.refreshPush();
      this.busy.set(false);
    }
  }

  private async refreshPush(): Promise<void> {
    let subscribed = false;
    try {
      const subscription = await currentSubscription();
      subscribed = subscription !== null;
      this.thisDeviceId.set(subscription ? await subscriptionId(subscription.endpoint) : null);
    } catch {
      // navigateur sans Web Push : le statut le dira
    }
    if (this.aw.vapidPublicKey) {
      try {
        this.devices.set(await this.aw.pushDevices());
      } catch {
        this.devices.set([]); // table pas encore créée (collecteur jamais lancé avec les notifications)
      }
    }
    this.pushState.set(pushStatus(readPushEnv(this.aw.vapidPublicKey !== undefined, subscribed)));
  }

  protected async changeEmail(): Promise<void> {
    this.busy.set(true);
    this.emailMessage.set(null);
    try {
      await this.aw.updateEmail(this.newEmail.trim(), this.password);
      this.emailMessage.set('Adresse e-mail mise à jour.');
    } catch (e) {
      this.emailMessage.set(this.message(e));
    } finally {
      this.password = '';
      this.busy.set(false);
    }
  }

  protected async addPasskey(): Promise<void> {
    await this.passkeyAction(() => this.aw.addPasskey(defaultPasskeyName()), 'Passkey ajoutée.');
  }

  protected async removePasskey(p: Models.Passkey): Promise<void> {
    await this.passkeyAction(() => this.aw.deletePasskey(p.$id), 'Passkey supprimée.');
  }

  protected async logout(): Promise<void> {
    await this.aw.logout();
  }

  private async passkeyAction(action: () => Promise<void>, done: string): Promise<void> {
    this.busy.set(true);
    this.passkeyMessage.set(null);
    try {
      await action();
      await this.refreshPasskeys();
      this.passkeyMessage.set(done);
    } catch (e) {
      this.passkeyMessage.set(isPasskeyCancelled(e) ? 'Opération annulée.' : this.message(e));
    } finally {
      this.busy.set(false);
    }
  }

  private async refreshPasskeys(): Promise<void> {
    try {
      this.passkeys.set(await this.aw.passkeys());
    } catch (e) {
      this.passkeyMessage.set(this.message(e));
    }
  }

  private message(e: unknown): string {
    if (e instanceof AppwriteException) {
      if (e.code === 429) return 'Trop de tentatives, réessaie dans quelques minutes.';
      if (e.code === 401 || e.code === 403)
        return 'Refusé. Pour les passkeys, reconnecte-toi (session de moins de 10 minutes requise) ; pour l’e-mail, vérifie le mot de passe.';
      if (e.code === 409) return 'Cette adresse e-mail est déjà utilisée.';
    }
    return e instanceof Error ? e.message : 'Erreur inattendue';
  }
}
