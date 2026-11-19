package fr.byj6n.conso.appwrite;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.byj6n.conso.CollectorProperties;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Vérifie les appels REST envoyés à Appwrite contre un faux serveur local. */
class AppwriteStoreTest {

    record Call(String method, String path, String key, String body) {
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int fileGetStatus = 404;
    private AppwriteStore store;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            String path = ex.getRequestURI().getPath();
            calls.add(new Call(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("X-Appwrite-Key"), body));
            int status = ex.getRequestMethod().equals("GET") && path.contains("/files/") ? fileGetStatus : 200;
            byte[] out = "{}".getBytes();
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        var cfg = new CollectorProperties.Appwrite("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "proj", "secret-key", "conso", "invoices", "users");
        store = new AppwriteStore(new AppwriteRest(cfg, mapper), cfg);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void upsertReleve() throws Exception {
        store.upsert(new Reading("gdb", "electricity", "month", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                882, "kWh", 311.0, 571.0, 4.0));

        Call c = calls.getFirst();
        assertThat(c.method()).isEqualTo("PUT");
        assertThat(c.path()).isEqualTo("/v1/tablesdb/conso/tables/readings/rows/gdb-electricity-2026-01-31");
        assertThat(c.key()).isEqualTo("secret-key");
        JsonNode data = mapper.readTree(c.body()).path("data");
        assertThat(data.path("quantity").asDouble()).isEqualTo(882.0);
        assertThat(data.path("periodEnd").asText()).isEqualTo("2026-01-31T12:00:00.000+00:00");
    }

    @Test
    void factureNouvelleEnvoieLePdf() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        Invoice inv = new Invoice("ileo", "water", "1000000003", LocalDate.of(2026, 11, 13), 295.87, 64.0, 64.0, "DOC", null);

        boolean uploaded = store.upsert(inv, () -> {
            downloads.incrementAndGet();
            return "%PDF-1.7 fake".getBytes();
        });

        assertThat(uploaded).isTrue();
        assertThat(downloads).hasValue(1);
        assertThat(calls).extracting(Call::method).containsExactly("GET", "POST", "PUT");
        Call upload = calls.get(1);
        assertThat(upload.path()).isEqualTo("/v1/storage/buckets/invoices/files");
        assertThat(upload.body()).contains("name=\"fileId\"\r\n\r\nileo-1000000003\r\n", "%PDF-1.7 fake");
        assertThat(mapper.readTree(calls.get(2).body()).path("data").path("fileId").asText()).isEqualTo("ileo-1000000003");
    }

    @Test
    void factureDejaPresenteNeRetelechargePas() throws Exception {
        fileGetStatus = 200;
        AtomicInteger downloads = new AtomicInteger();
        Invoice inv = new Invoice("gdb", "electricity", "88000001", LocalDate.of(2026, 7, 5), 1396.05, 0.0, null, "x", 1L);

        boolean uploaded = store.upsert(inv, () -> {
            downloads.incrementAndGet();
            return new byte[0];
        });

        assertThat(uploaded).isFalse();
        assertThat(downloads).hasValue(0);
        assertThat(calls).extracting(Call::method).containsExactly("GET", "PUT");
    }
}
