package fr.byj6n.conso.port;

import java.io.IOException;

/**
 * Port : un fournisseur d'énergie ou d'eau. Le client est sans état ; chaque collecte ouvre sa propre
 * {@link ProviderSession} avec {@link #connect()}, ce qui évite de partager un jeton ou des cookies
 * entre deux collectes.
 */
public interface ProviderClient {

    /** Identifiant court du fournisseur ("gdb", "ileo"), utilisé dans les logs et les identifiants de lignes. */
    String provider();

    /** Ouvre une session authentifiée chez le fournisseur. */
    ProviderSession connect() throws IOException, InterruptedException;
}
