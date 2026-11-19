package fr.byj6n.conso.appwrite;

import fr.byj6n.conso.CollectorProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crée (de façon idempotente) la base, les tables et le bucket au démarrage. Un 409 (déjà existant)
 * est ignoré : on peut relancer autant de fois que voulu.
 */
public class AppwriteSchema {

    public static final String READINGS = "readings";
    public static final String INVOICES = "invoices";
    public static final String TARIFFS = "tariffs";

    private static final Logger log = LoggerFactory.getLogger(AppwriteSchema.class);

    private record Column(String type, String key, boolean required, Integer size) {
        static Column str(String key, int size, boolean required) {
            return new Column("string", key, required, size);
        }

        static Column num(String key, boolean required) {
            return new Column("float", key, required, null);
        }

        static Column date(String key) {
            return new Column("datetime", key, true, null);
        }
    }

    private static final Map<String, List<Column>> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put(
                READINGS,
                List.of(
                        Column.str("provider", 16, true),
                        Column.str("energy", 16, true),
                        Column.str("granularity", 16, true),
                        Column.date("periodStart"),
                        Column.date("periodEnd"),
                        Column.num("quantity", true),
                        Column.str("unit", 8, true),
                        Column.num("hp", false),
                        Column.num("hc", false),
                        Column.num("avgTemperature", false)));
        TABLES.put(
                INVOICES,
                List.of(
                        Column.str("provider", 16, true),
                        Column.str("energy", 16, true),
                        Column.str("number", 32, true),
                        Column.date("date"),
                        Column.num("amount", false),
                        Column.num("amountDue", false),
                        Column.num("volume", false),
                        Column.str("fileId", 64, false)));
        TABLES.put(
                TARIFFS,
                List.of(
                        Column.str("provider", 16, true),
                        Column.str("energy", 16, true),
                        Column.date("validFrom"),
                        Column.str("offer", 128, false),
                        Column.num("kwh", false),
                        Column.num("hp", false),
                        Column.num("hc", false),
                        Column.num("subscriptionMonthly", false)));
    }

    private final AppwriteRest api;
    private final CollectorProperties.Appwrite cfg;

    public AppwriteSchema(AppwriteRest api, CollectorProperties.Appwrite cfg) {
        this.api = api;
        this.cfg = cfg;
    }

    public void ensure() throws IOException, InterruptedException {
        String db = cfg.databaseId();
        String readPerm = "read(\"" + cfg.readRole() + "\")";

        ignoreConflict(
                api.post("/tablesdb", Map.of("databaseId", db, "name", "Consommations")),
                "base " + db);

        for (var table : TABLES.entrySet()) {
            String t = table.getKey();
            Map<String, Object> tableCfg =
                    Map.of("name", t, "permissions", List.of(readPerm), "rowSecurity", false);
            createOrUpdate(
                    "/tablesdb/" + db + "/tables",
                    withId("tableId", t, tableCfg),
                    "/tablesdb/" + db + "/tables/" + t,
                    tableCfg,
                    "table " + t);
            for (Column c : table.getValue()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("key", c.key());
                body.put("required", c.required());
                if (c.size() != null) body.put("size", c.size());
                ignoreConflict(
                        api.post("/tablesdb/" + db + "/tables/" + t + "/columns/" + c.type(), body),
                        "colonne " + t + "." + c.key());
            }
            waitForColumns(db, t);
        }

        Map<String, Object> bucketCfg =
                Map.of(
                        "name",
                        "Factures",
                        "permissions",
                        List.of(readPerm),
                        "fileSecurity",
                        false,
                        "allowedFileExtensions",
                        List.of("pdf"));
        createOrUpdate(
                "/storage/buckets",
                withId("bucketId", cfg.bucketId(), bucketCfg),
                "/storage/buckets/" + cfg.bucketId(),
                bucketCfg,
                "bucket " + cfg.bucketId());
        log.info(
                "Schéma Appwrite prêt (base '{}', bucket '{}', lecture : {})",
                db,
                cfg.bucketId(),
                cfg.readRole());
    }

    /** Les colonnes sont créées en asynchrone : on attend qu'elles soient "available". */
    private void waitForColumns(String db, String table) throws IOException, InterruptedException {
        for (int i = 0; i < 30; i++) {
            var r =
                    AppwriteRest.check(
                            api.get("/tablesdb/" + db + "/tables/" + table + "/columns"),
                            "colonnes " + table);
            boolean ready = true;
            for (var col : r.body().path("columns")) {
                String status = col.path("status").asText("available");
                if (status.equals("failed")) {
                    throw new IOException(
                            "Appwrite : colonne "
                                    + table
                                    + "."
                                    + col.path("key").asText()
                                    + " en échec");
                }
                ready &= status.equals("available");
            }
            if (ready) return;
            Thread.sleep(1000);
        }
        throw new IOException("Appwrite : colonnes de " + table + " toujours en cours de création");
    }

    /**
     * Crée la ressource ; si elle existe déjà (409), réaligne ses permissions sur la configuration.
     * Sans ça, resserrer CONSO_APPWRITE_READ_ROLE n'aurait aucun effet sur les tables et le bucket
     * déjà créés.
     */
    private void createOrUpdate(
            String createPath,
            Map<String, Object> createBody,
            String updatePath,
            Map<String, Object> updateBody,
            String what)
            throws IOException, InterruptedException {
        var created = api.post(createPath, createBody);
        if (created.status() == 409) {
            AppwriteRest.check(
                    api.put(updatePath, updateBody), "mise à jour des permissions de " + what);
        } else {
            AppwriteRest.check(created, "création " + what);
        }
    }

    private static Map<String, Object> withId(String idKey, String id, Map<String, Object> rest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(idKey, id);
        body.putAll(rest);
        return body;
    }

    private static void ignoreConflict(AppwriteRest.Result r, String what) throws IOException {
        if (r.status() == 409) return;
        AppwriteRest.check(r, "création " + what);
    }
}
