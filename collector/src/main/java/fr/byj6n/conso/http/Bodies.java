package fr.byj6n.conso.http;

import java.io.IOException;
import java.io.InputStream;

/**
 * Lecture de corps de réponse avec plafond : un serveur défaillant ne doit pas pouvoir saturer la
 * mémoire du conteneur.
 */
public final class Bodies {

    /** Un PDF de facture fait quelques centaines de Ko ; Appwrite en accepte 5 Mo ici. */
    public static final int MAX_PDF_BYTES = 5 * 1024 * 1024;

    private Bodies() {}

    /** Vrai si les octets commencent par la signature d'un PDF ({@code %PDF-}). */
    public static boolean isPdf(byte[] data) {
        return data.length >= 5
                && data[0] == '%'
                && data[1] == 'P'
                && data[2] == 'D'
                && data[3] == 'F'
                && data[4] == '-';
    }

    /**
     * Lit tout le flux, à condition qu'il ne dépasse pas {@code max} octets.
     *
     * @throws IOException si le flux dépasse {@code max} octets
     */
    public static byte[] readAtMost(InputStream in, int max) throws IOException {
        try (in) {
            byte[] data = in.readNBytes(max + 1);
            if (data.length > max) {
                throw new IOException("réponse trop volumineuse (plus de " + max / 1024 + " Ko)");
            }
            return data;
        }
    }
}
