package fr.byj6n.conso.port;

import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;

import java.io.IOException;
import java.util.List;

/** Session authentifiée chez un fournisseur, valable le temps d'une collecte. */
public interface ProviderSession {

    List<Reading> readings() throws IOException, InterruptedException;

    List<Invoice> invoices() throws IOException, InterruptedException;

    /** Tarifs en vigueur. Optionnel : un fournisseur qui ne les publie pas renvoie une liste vide. */
    default List<Tariff> tariffs() throws IOException, InterruptedException {
        return List.of();
    }

    byte[] downloadPdf(Invoice invoice) throws IOException, InterruptedException;
}
