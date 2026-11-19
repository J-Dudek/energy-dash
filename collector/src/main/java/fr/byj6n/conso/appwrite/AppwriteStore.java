package fr.byj6n.conso.appwrite;

import fr.byj6n.conso.CollectorProperties;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;

import java.io.IOException;
import java.util.Map;

/** Écrit relevés, factures et tarifs dans Appwrite. Les upserts par identifiant déterministe rendent la collecte rejouable. */
public class AppwriteStore {

    @FunctionalInterface
    public interface PdfSupplier {
        byte[] get() throws IOException, InterruptedException;
    }

    private final AppwriteRest api;
    private final CollectorProperties.Appwrite cfg;

    public AppwriteStore(AppwriteRest api, CollectorProperties.Appwrite cfg) {
        this.api = api;
        this.cfg = cfg;
    }

    public void upsert(Reading r) throws IOException, InterruptedException {
        upsertRow(AppwriteSchema.READINGS, r.rowId(), AppwriteRows.of(r));
    }

    public void upsert(Tariff t) throws IOException, InterruptedException {
        upsertRow(AppwriteSchema.TARIFFS, t.rowId(), AppwriteRows.of(t));
    }

    /**
     * Enregistre la facture ; le PDF n'est téléchargé chez le fournisseur que s'il n'est pas
     * déjà dans le bucket.
     *
     * @return vrai si un nouveau PDF a été envoyé
     */
    public boolean upsert(Invoice inv, PdfSupplier pdf) throws IOException, InterruptedException {
        String fileId = inv.rowId();
        boolean uploaded = false;
        if (api.get("/storage/buckets/" + cfg.bucketId() + "/files/" + fileId).status() == 404) {
            String name = inv.provider() + "-" + inv.energy() + "-" + inv.date() + "-" + inv.number() + ".pdf";
            AppwriteRest.check(api.uploadFile(cfg.bucketId(), fileId, name, pdf.get()), "upload " + name);
            uploaded = true;
        }
        upsertRow(AppwriteSchema.INVOICES, inv.rowId(), AppwriteRows.of(inv, fileId));
        return uploaded;
    }

    private void upsertRow(String table, String rowId, Map<String, Object> data) throws IOException, InterruptedException {
        AppwriteRest.check(api.put("/tablesdb/" + cfg.databaseId() + "/tables/" + table + "/rows/" + rowId,
                Map.of("data", data)), "upsert " + table + "/" + rowId);
    }
}
