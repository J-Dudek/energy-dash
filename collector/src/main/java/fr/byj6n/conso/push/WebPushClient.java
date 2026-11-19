package fr.byj6n.conso.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Envoie un message chiffré à un push service (FCM, Apple, Mozilla, Windows). L'adresse vient d'une
 * ligne que l'utilisateur écrit lui-même dans Appwrite : on n'appelle donc que les push services
 * connus, jamais une adresse quelconque (sinon le NAS ferait des requêtes où on le lui demande).
 */
public class WebPushClient {

    /** Ce que le push service a répondu. */
    public enum Outcome {
        DELIVERED,
        /** 404 / 410 : l'abonnement n'existe plus, à supprimer. */
        GONE,
        /** 429 / 5xx : à retenter à la prochaine occasion. */
        RETRY_LATER,
        /** Refus définitif (clés, taille, adresse non autorisée) : voir les journaux. */
        REJECTED
    }

    private static final Duration TTL = Duration.ofDays(1);

    private final Vapid vapid;
    private final Predicate<URI> endpointPolicy;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WebPushClient(Vapid vapid) {
        this(vapid, WebPushClient::isKnownPushService);
    }

    WebPushClient(Vapid vapid, Predicate<URI> endpointPolicy) {
        this.vapid = vapid;
        this.endpointPolicy = endpointPolicy;
    }

    public Outcome send(Subscription subscription, byte[] payload)
            throws IOException, InterruptedException {
        URI endpoint;
        byte[] ua;
        byte[] auth;
        try {
            endpoint = URI.create(subscription.endpoint());
            ua = Base64.getUrlDecoder().decode(subscription.p256dh());
            auth = Base64.getUrlDecoder().decode(subscription.auth());
        } catch (IllegalArgumentException e) {
            return Outcome.REJECTED;
        }
        if (!endpointPolicy.test(endpoint)) return Outcome.REJECTED;

        byte[] body;
        String authorization;
        try {
            body = WebPushCrypto.encrypt(payload, ua, auth);
            authorization = vapid.authorization(origin(endpoint), Instant.now());
        } catch (GeneralSecurityException e) {
            return Outcome.REJECTED;
        }
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", authorization)
                        .header("Content-Encoding", "aes128gcm")
                        .header("Content-Type", "application/octet-stream")
                        .header("TTL", Long.toString(TTL.toSeconds()))
                        .header("Urgency", "normal")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        // redirections non suivies (réglage par défaut du client HTTP) : le push service répond
        // directement
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        return outcome(status);
    }

    static Outcome outcome(int status) {
        if (status >= 200 && status < 300) return Outcome.DELIVERED;
        if (status == 404 || status == 410) return Outcome.GONE;
        if (status == 429 || status >= 500) return Outcome.RETRY_LATER;
        return Outcome.REJECTED;
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** https, port par défaut, sans identifiants, et un des push services des navigateurs. */
    static boolean isKnownPushService(URI uri) {
        if (uri.getScheme() == null
                || !uri.getScheme().equals("https")
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("fcm.googleapis.com") // Chrome, Edge, Android
                || host.endsWith(".push.apple.com") // Safari, iPhone, iPad, Mac
                || host.equals("web.push.apple.com")
                || host.endsWith(".push.services.mozilla.com") // Firefox
                || host.endsWith(".notify.windows.com"); // Edge sous Windows
    }
}
