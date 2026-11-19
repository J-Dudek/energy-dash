/**
 * Passkeys (WebAuthn) côté navigateur : les options viennent d'Appwrite en JSON, le navigateur les convertit,
 * l'appareil signe (biométrie ou code), et on renvoie le résultat en JSON.
 */

/** Les méthodes JSON de WebAuthn sont récentes (Safari 18.4, Chrome 129, Firefox 119) : sans elles, on n'affiche rien. */
export function passkeysSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof PublicKeyCredential !== 'undefined' &&
    typeof PublicKeyCredential.parseCreationOptionsFromJSON === 'function' &&
    typeof PublicKeyCredential.parseRequestOptionsFromJSON === 'function' &&
    typeof navigator.credentials !== 'undefined'
  );
}

/** Erreur « normale » : l'utilisateur a fermé la fenêtre de l'appareil ou le délai est écoulé. */
export function isPasskeyCancelled(e: unknown): boolean {
  return e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError');
}

/** Demande à l'appareil de créer une passkey ; renvoie la preuve à transmettre à Appwrite. */
export async function createCredential(publicKey: object): Promise<object> {
  const options = PublicKeyCredential.parseCreationOptionsFromJSON(
    publicKey as PublicKeyCredentialCreationOptionsJSON,
  );
  const credential = await navigator.credentials.create({ publicKey: options });
  return toJson(credential);
}

/** Demande à l'appareil de signer le défi avec l'une des passkeys du site. */
export async function getCredential(publicKey: object): Promise<object> {
  const options = PublicKeyCredential.parseRequestOptionsFromJSON(
    publicKey as PublicKeyCredentialRequestOptionsJSON,
  );
  const credential = await navigator.credentials.get({ publicKey: options });
  return toJson(credential);
}

function toJson(credential: Credential | null): object {
  if (!(credential instanceof PublicKeyCredential)) {
    throw new Error("L'appareil n'a pas fourni de passkey.");
  }
  return credential.toJSON();
}

/** Nom proposé à une nouvelle passkey : l'appareil et la date, pour s'y retrouver dans la liste. */
export function defaultPasskeyName(): string {
  return `${navigator.platform || 'Appareil'} · ${new Date().toLocaleDateString('fr-FR')}`;
}
