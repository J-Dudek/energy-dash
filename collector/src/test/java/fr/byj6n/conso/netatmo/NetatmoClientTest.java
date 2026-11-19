package fr.byj6n.conso.netatmo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import fr.byj6n.conso.CollectorProperties;
import fr.byj6n.conso.model.Reading;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Faux serveur Netatmo local : jamais d'appel à la vraie API. */
class NetatmoClientTest {

    record Call(String method, String path, String query, String auth, String body) {}

    @TempDir Path dir;

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int tokenStatus = 200;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/",
                ex -> {
                    String body =
                            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String path = ex.getRequestURI().getPath();
                    calls.add(
                            new Call(
                                    ex.getRequestMethod(),
                                    path,
                                    ex.getRequestURI().getRawQuery(),
                                    ex.getRequestHeaders().getFirst("Authorization"),
                                    body));
                    int status = 200;
                    String out =
                            switch (path) {
                                case "/oauth2/token" -> {
                                    status = tokenStatus;
                                    yield tokenStatus == 200
                                            ? "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-2\",\"expires_in\":10800}"
                                            : "{\"error\":\"invalid_grant\"}";
                                }
                                case "/api/homesdata" ->
                                        """
                        {"body":{"homes":[{"modules":[{"id":"RELAY","type":"NAPlug"},
                          {"id":"THERM","type":"NATherm1","bridge":"RELAY"}]}]}}""";
                                case "/api/getmeasure" ->
                                        "{\"status\":\"ok\",\"body\":{\"1763420400\":[7200],\"1763506800\":[3600]}}";
                                default -> {
                                    status = 404;
                                    yield "{}";
                                }
                            };
                    byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(status, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NetatmoClient client(String refreshToken, String deviceId, String moduleId) {
        var cfg =
                new CollectorProperties.Netatmo(
                        true,
                        "cid",
                        "csecret",
                        refreshToken,
                        dir.resolve("netatmo-token.json").toString(),
                        "sum_boiler_on",
                        400,
                        deviceId,
                        moduleId);
        return new NetatmoClient(cfg, mapper, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String decoded(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    @Test
    void collecteLeTempsDeChauffe() throws Exception {
        List<Reading> readings = client("refresh-1", null, null).connect().readings();

        assertThat(readings).hasSize(2);
        assertThat(readings).extracting(Reading::quantity).containsExactly(2.0, 1.0);

        Call token = calls.getFirst();
        assertThat(token.method()).isEqualTo("POST");
        assertThat(decoded(token.body()))
                .contains(
                        "grant_type=refresh_token",
                        "refresh_token=refresh-1",
                        "client_id=cid",
                        "client_secret=csecret");

        Call measure =
                calls.stream()
                        .filter(c -> c.path().equals("/api/getmeasure"))
                        .findFirst()
                        .orElseThrow();
        assertThat(measure.auth())
                .isEqualTo("Bearer access-1"); // jeton en en-tête, jamais dans l'URL
        assertThat(decoded(measure.query()))
                .contains(
                        "device_id=RELAY",
                        "module_id=THERM",
                        "scale=1day",
                        "type=sum_boiler_on",
                        "optimize=false",
                        "real_time=true");
        assertThat(measure.query()).doesNotContain("access-1");
    }

    @Test
    void leNouveauJetonDeRenouvellementEstConserve() throws Exception {
        client("refresh-1", null, null).connect();
        calls.clear();

        client("refresh-1", null, null).connect(); // 2e collecte : le .env n'a pas bougé

        assertThat(decoded(calls.getFirst().body())).contains("refresh_token=refresh-2");
    }

    @Test
    void identifiantsConfiguresEvitentLaDecouverte() throws Exception {
        client("refresh-1", "MY-RELAY", "MY-THERM").connect().readings();

        assertThat(calls).noneMatch(c -> c.path().equals("/api/homesdata"));
        assertThat(decoded(calls.getLast().query()))
                .contains("device_id=MY-RELAY", "module_id=MY-THERM");
    }

    @Test
    void jetonRefuseDonneUnMessageActionnable() {
        tokenStatus = 400;

        assertThatThrownBy(() -> client("expired", null, null).connect())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid_grant")
                .hasMessageContaining("CONSO_NETATMO_REFRESH_TOKEN")
                .hasMessageNotContaining("expired");
    }

    @Test
    void pasDeFacture() throws Exception {
        assertThat(client("refresh-1", null, null).connect().invoices()).isEmpty();
    }
}
