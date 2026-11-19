package fr.byj6n.conso.appwrite;

import fr.byj6n.conso.CollectorProperties;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import fr.byj6n.conso.push.Subscription;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Écrit relevés, factures et tarifs dans Appwrite. Les upserts par identifiant déterministe rendent
 * la collecte rejouable.
 */
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

    /** Ce relevé est-il déjà en base ? (sert à ne signaler que les mois qui apparaissent.) */
    public boolean exists(Reading r) throws IOException, InterruptedException {
        return api.get(rowPath(AppwriteSchema.READINGS, r.rowId())).status() != 404;
    }

    public void upsert(Tariff t) throws IOException, InterruptedException {
        upsertRow(AppwriteSchema.TARIFFS, t.rowId(), AppwriteRows.of(t));
    }

    /**
     * Résultat de l'enregistrement d'une facture.
     *
     * @param pdfUploaded un nouveau PDF a été envoyé
     * @param created la facture n'existait pas encore (c'est une nouveauté, pas une relecture)
     */
    public record InvoiceWrite(boolean pdfUploaded, boolean created) {}

    /**
     * Enregistre la facture ; le PDF n'est téléchargé chez le fournisseur que s'il n'est pas déjà
     * dans le bucket.
     */
    public InvoiceWrite upsert(Invoice inv, PdfSupplier pdf)
            throws IOException, InterruptedException {
        String fileId = inv.rowId();
        boolean created = api.get(rowPath(AppwriteSchema.INVOICES, inv.rowId())).status() == 404;
        boolean uploaded = false;
        if (api.get("/storage/buckets/" + cfg.bucketId() + "/files/" + fileId).status() == 404) {
            String name =
                    inv.provider()
                            + "-"
                            + inv.energy()
                            + "-"
                            + inv.date()
                            + "-"
                            + inv.number()
                            + ".pdf";
            AppwriteRest.check(
                    api.uploadFile(cfg.bucketId(), fileId, name, pdf.get()), "upload " + name);
            uploaded = true;
        }
        upsertRow(AppwriteSchema.INVOICES, inv.rowId(), AppwriteRows.of(inv, fileId));
        return new InvoiceWrite(uploaded, created);
    }

    /** Appareils abonnés aux notifications (écrits par le dashboard, lus ici avec la clé API). */
    public List<Subscription> pushSubscriptions() throws IOException, InterruptedException {
        List<Subscription> out = new ArrayList<>();
        int page = 100;
        for (int offset = 0; ; offset += page) {
            String queries =
                    "?queries%5B0%5D="
                            + encode("{\"method\":\"limit\",\"values\":[" + page + "]}")
                            + "&queries%5B1%5D="
                            + encode("{\"method\":\"offset\",\"values\":[" + offset + "]}");
            var r =
                    AppwriteRest.check(
                            api.get(
                                    "/tablesdb/"
                                            + cfg.databaseId()
                                            + "/tables/"
                                            + AppwriteSchema.PUSH_SUBSCRIPTIONS
                                            + "/rows"
                                            + queries),
                            "liste des abonnements");
            var rows = r.body().path("rows");
            for (var row : rows) {
                out.add(
                        new Subscription(
                                row.path("$id").asText(),
                                row.path("endpoint").asText(),
                                row.path("p256dh").asText(),
                                row.path("auth").asText()));
            }
            if (rows.size() < page) return out;
        }
    }

    public void deletePushSubscription(String id) throws IOException, InterruptedException {
        AppwriteRest.check(
                api.delete(rowPath(AppwriteSchema.PUSH_SUBSCRIPTIONS, id)),
                "suppression de l'abonnement " + id);
    }

    private String rowPath(String table, String rowId) {
        return "/tablesdb/" + cfg.databaseId() + "/tables/" + table + "/rows/" + rowId;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void upsertRow(String table, String rowId, Map<String, Object> data)
            throws IOException, InterruptedException {
        AppwriteRest.check(
                api.put(rowPath(table, rowId), Map.of("data", data)),
                "upsert " + table + "/" + rowId);
    }
}
