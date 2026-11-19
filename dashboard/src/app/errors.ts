import { AppwriteException } from 'appwrite';

/** Message affichable pour une erreur d'appel Appwrite ou réseau. */
export function errorMessage(e: unknown): string {
  if (e instanceof AppwriteException && e.code === 429)
    return 'Trop de tentatives, réessaie dans quelques minutes.';
  return e instanceof Error ? e.message : 'Erreur inattendue';
}
