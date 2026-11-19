package fr.byj6n.conso.appwrite;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import fr.byj6n.conso.CollectorProperties;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Le schéma existe déjà : un changement de CONSO_APPWRITE_READ_ROLE doit quand même s'appliquer.
 */
class AppwriteSchemaTest {

    record Call(String method, String path, String body) {}

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private AppwriteSchema schema;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/v1",
                ex -> {
                    String body =
                            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), body));
                    // tout "existe déjà" : les créations répondent 409
                    int status = ex.getRequestMethod().equals("POST") ? 409 : 200;
                    byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(status, out.length);
                    ex.getResponseBody().write(out);
                    ex.close();
                });
        server.start();
        var cfg =
                new CollectorProperties.Appwrite(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "proj",
                        "key",
                        "conso",
                        "invoices",
                        "user:abc123");
        schema = new AppwriteSchema(new AppwriteRest(cfg, new ObjectMapper()), cfg);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void reapliqueLesPermissionsSurLesTablesEtLeBucketExistants() throws Exception {
        schema.ensure();

        for (String table : List.of("readings", "invoices", "tariffs")) {
            Call put =
                    calls.stream()
                            .filter(
                                    c ->
                                            c.method().equals("PUT")
                                                    && c.path()
                                                            .equals(
                                                                    "/v1/tablesdb/conso/tables/"
                                                                            + table))
                            .findFirst()
                            .orElseThrow();
            assertThat(put.body())
                    .contains("read(\\\"user:abc123\\\")")
                    .contains("\"rowSecurity\":false");
        }
        Call push =
                calls.stream()
                        .filter(
                                c ->
                                        c.method().equals("PUT")
                                                && c.path()
                                                        .equals(
                                                                "/v1/tablesdb/conso/tables/push_subscriptions"))
                        .findFirst()
                        .orElseThrow();
        // les appareils créent leurs abonnements ; chaque ligne n'est lisible que par son
        // propriétaire
        assertThat(push.body())
                .contains("create(\\\"user:abc123\\\")")
                .contains("\"rowSecurity\":true")
                .doesNotContain("read(");
        Call bucket =
                calls.stream()
                        .filter(
                                c ->
                                        c.method().equals("PUT")
                                                && c.path().equals("/v1/storage/buckets/invoices"))
                        .findFirst()
                        .orElseThrow();
        assertThat(bucket.body())
                .contains("read(\\\"user:abc123\\\")")
                .contains("\"allowedFileExtensions\":[\"pdf\"]");
    }
}
