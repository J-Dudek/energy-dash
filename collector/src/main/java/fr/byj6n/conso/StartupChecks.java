package fr.byj6n.conso;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuse de démarrer sur une configuration dangereuse plutôt que de l'utiliser : la clé API
 * Appwrite et les identifiants des fournisseurs ne doivent jamais circuler en clair sur Internet.
 */
@Component
class StartupChecks {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);
    private static final Pattern IPV4 =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private final CollectorProperties props;

    StartupChecks(CollectorProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationStartedEvent.class)
    void run() {
        requireSecureEndpoint(props.appwrite().endpoint());
        if ("users".equals(props.appwrite().readRole())) {
            log.warn(
                    "Appwrite : lecture ouverte à tout compte du projet (CONSO_APPWRITE_READ_ROLE=users). "
                            + "Désactive l'inscription libre dans la console, ou restreins à user:<id> / team:<id> : "
                            + "les factures PDF contiennent des données personnelles.");
        }
    }

    /**
     * HTTPS obligatoire, sauf vers une machine du réseau local ou la boucle locale (Appwrite sur le
     * NAS).
     *
     * @throws IllegalStateException si l'endpoint enverrait la clé API en clair sur un réseau
     *     public
     */
    static void requireSecureEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CONSO_APPWRITE_ENDPOINT invalide");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            throw new IllegalStateException(
                    "CONSO_APPWRITE_ENDPOINT invalide : schéma et hôte requis");
        }
        if (scheme.equalsIgnoreCase("https")) return;
        if (scheme.equalsIgnoreCase("http") && isLocalHost(host)) return;
        throw new IllegalStateException(
                "CONSO_APPWRITE_ENDPOINT doit être en https (la clé API serait envoyée en clair) : "
                        + "http n'est accepté que vers localhost ou une adresse du réseau local");
    }

    static boolean isLocalHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost")
                || h.equals("::1")
                || h.equals("[::1]")
                || h.endsWith(".local")
                || h.endsWith(".lan")
                || h.endsWith(".internal")) {
            return true;
        }
        var m = IPV4.matcher(h);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return a == 127
                    || a == 10
                    || (a == 192 && b == 168)
                    || (a == 172 && b >= 16 && b <= 31);
        }
        return !h.contains("."); // nom court : service docker ou hôte du réseau interne
    }
}
