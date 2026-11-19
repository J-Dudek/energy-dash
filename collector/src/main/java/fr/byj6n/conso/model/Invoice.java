package fr.byj6n.conso.model;

import java.time.LocalDate;

/**
 * Une facture fournisseur.
 *
 * @param sourceRef référence opaque permettant au client fournisseur de télécharger le PDF
 *                  (documentId Gaz de Bordeaux, cleDoc Iléo). Jamais stockée dans Appwrite.
 */
public record Invoice(
        String provider,
        String energy,
        String number,
        LocalDate date,
        Double amount,
        Double amountDue,
        Double volume,
        String sourceRef,
        Long contractId) {

    public String rowId() {
        return Ids.of(provider + "-" + number);
    }
}
