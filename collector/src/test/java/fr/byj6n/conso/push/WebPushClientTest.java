package fr.byj6n.conso.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WebPushClientTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private HttpServer server;
    private volatile int status = 201;
    private final AtomicReference<com.sun.net.httpserver.Headers> seen = new AtomicReference<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();
    private WebPushClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/push",
                ex -> {
                    seen.set(ex.getRequestHeaders());
                    seenBody.set(ex.getRequestBody().readAllBytes());
                    ex.sendResponseHeaders(status, -1);
                    ex.close();
                });
        server.start();
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = gen.generateKeyPair();
        byte[] s = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
        byte[] scalar = new byte[32];
        int n = Math.min(32, s.length);
        System.arraycopy(s, s.length - n, scalar, 32 - n, n);
        Vapid vapid =
                Vapid.of(
                        B64.encodeToString(EcKeys.uncompressed((ECPublicKey) pair.getPublic())),
                        B64.encodeToString(scalar),
                        "mailto:moi@example.org",
                        new ObjectMapper());
        // le test parle à un faux push service local : on ouvre la liste blanche à lui seul
        client = new WebPushClient(vapid, uri -> uri.getHost().equals("127.0.0.1"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Subscription subscription(String path) {
        return new Subscription(
                "s1", "http://127.0.0.1:" + server.getAddress().getPort() + path, UA_PUBLIC, AUTH);
    }

    @Test
    void envoieUnMessageChiffreAvecLesEntetesWebPush() throws Exception {
        var outcome =
                client.send(
                        subscription("/push"),
                        "{\"title\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(outcome).isEqualTo(WebPushClient.Outcome.DELIVERED);
        assertThat(seen.get().getFirst("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(seen.get().getFirst("TTL")).isEqualTo("86400");
        assertThat(seen.get().getFirst("Authorization")).startsWith("vapid t=").contains(", k=");
        assertThat(seenBody.get()).hasSizeGreaterThan(86); // en-tête + enregistrement chiffré
    }

    @Test
    void traduitLesCodesDeReponse() {
        assertThat(WebPushClient.outcome(201)).isEqualTo(WebPushClient.Outcome.DELIVERED);
        assertThat(WebPushClient.outcome(404)).isEqualTo(WebPushClient.Outcome.GONE);
        assertThat(WebPushClient.outcome(410)).isEqualTo(WebPushClient.Outcome.GONE);
        assertThat(WebPushClient.outcome(429)).isEqualTo(WebPushClient.Outcome.RETRY_LATER);
        assertThat(WebPushClient.outcome(503)).isEqualTo(WebPushClient.Outcome.RETRY_LATER);
        assertThat(WebPushClient.outcome(403)).isEqualTo(WebPushClient.Outcome.REJECTED);
    }

    @Test
    void remonteLaSuppressionDUnAbonnementExpire() throws Exception {
        status = 410;

        assertThat(client.send(subscription("/push"), new byte[] {1}))
                .isEqualTo(WebPushClient.Outcome.GONE);
    }

    @Test
    void refuseUneCleDAbonnementMalFormee() throws Exception {
        var bad = new Subscription("s2", subscription("/push").endpoint(), "%%%", AUTH);

        assertThat(client.send(bad, new byte[] {1})).isEqualTo(WebPushClient.Outcome.REJECTED);
        assertThat(seen.get()).isNull(); // rien n'est parti
    }

    @Test
    void neContacteQueLesPushServicesConnus() {
        for (String ok :
                new String[] {
                    "https://fcm.googleapis.com/fcm/send/abc",
                    "https://web.push.apple.com/Qabc",
                    "https://updates.push.services.mozilla.com/wpush/v2/abc",
                    "https://wns2-par02p.notify.windows.com/w/?token=abc"
                }) {
            assertThat(WebPushClient.isKnownPushService(URI.create(ok))).as(ok).isTrue();
        }
        for (String ko :
                new String[] {
                    "http://fcm.googleapis.com/x", // pas en https
                    "https://192.168.1.10/x", // réseau local
                    "https://localhost/x",
                    "https://169.254.169.254/latest/meta-data",
                    "https://evil.example/fcm.googleapis.com",
                    "https://fcm.googleapis.com.evil.example/x",
                    "https://user@fcm.googleapis.com/x", // identifiants dans l'URL
                    "https://fcm.googleapis.com:8443/x", // port inhabituel
                    "https://notpush.apple.com.evil.example/x"
                }) {
            assertThat(WebPushClient.isKnownPushService(URI.create(ko))).as(ko).isFalse();
        }
    }
}
