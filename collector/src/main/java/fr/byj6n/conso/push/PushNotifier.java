package fr.byj6n.conso.push;

import fr.byj6n.conso.alert.Alert;
import fr.byj6n.conso.appwrite.AppwriteStore;
import fr.byj6n.conso.model.Invoice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Prévient les appareils abonnés quand une nouvelle facture arrive. Une panne de notification ne
 * doit jamais faire échouer la collecte : tout est journalisé et avalé ici.
 */
public class PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

    private static final Map<String, String> PROVIDERS =
            Map.of("gdb", "Gaz de Bordeaux", "ileo", "Iléo");
    private static final Map<String, String> ENERGIES =
            Map.of("electricity", "électricité", "gas", "gaz", "water", "eau");

    private final AppwriteStore store;
    private final WebPushClient client;
    private final ObjectMapper mapper;

    /**
     * @param client {@code null} quand les notifications ne sont pas configurées
     */
    public PushNotifier(AppwriteStore store, WebPushClient client, ObjectMapper mapper) {
        this.store = store;
        this.client = client;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return client != null;
    }

    public void notifyNewInvoices(List<Invoice> invoices) throws InterruptedException {
        if (invoices.isEmpty()) return;
        broadcast(payload(invoices), invoices.size() + " facture(s)");
    }

    /** Alertes de consommation (un mois bien au-dessus du même mois l'an dernier). */
    public void notifyAlerts(List<Alert> alerts) throws InterruptedException {
        for (Alert alert : alerts) {
            broadcast(alertPayload(alert), "alerte " + alert.energy());
        }
    }

    /** Un échec d'envoi est journalisé et avalé : la collecte ne doit jamais en dépendre. */
    private void broadcast(byte[] payload, String what) throws InterruptedException {
        if (!enabled()) return;
        try {
            List<Subscription> subscriptions = store.pushSubscriptions();
            if (subscriptions.isEmpty()) return;
            int delivered = 0;
            for (Subscription s : subscriptions) {
                if (deliver(s, payload)) delivered++;
            }
            log.info(
                    "Notification de {} : {}/{} appareil(s)",
                    what,
                    delivered,
                    subscriptions.size());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Notifications ignorées : {}", e.getMessage());
        }
    }

    private boolean deliver(Subscription s, byte[] payload) throws InterruptedException {
        try {
            WebPushClient.Outcome outcome = client.send(s, payload);
            switch (outcome) {
                case DELIVERED -> {
                    return true;
                }
                case GONE -> {
                    store.deletePushSubscription(s.id());
                    log.info("Abonnement {} expiré : supprimé", s.id());
                }
                case RETRY_LATER ->
                        log.warn("Abonnement {} : push service indisponible, ignoré", s.id());
                case REJECTED ->
                        log.warn(
                                "Abonnement {} refusé (adresse non autorisée, clés ou configuration"
                                        + " VAPID invalides)",
                                s.id());
            }
        } catch (IOException e) {
            log.warn("Abonnement {} : {}", s.id(), e.getMessage());
        }
        return false;
    }

    /**
     * Le contenu voyage chiffré de bout en bout jusqu'à l'appareil : le push service ne le lit pas.
     */
    byte[] payload(List<Invoice> invoices) {
        String title;
        String body;
        String url;
        String tag;
        if (invoices.size() == 1) {
            Invoice inv = invoices.get(0);
            title = "Nouvelle facture " + PROVIDERS.getOrDefault(inv.provider(), inv.provider());
            body = ENERGIES.getOrDefault(inv.energy(), inv.energy());
            if (inv.amount() != null) {
                body +=
                        " · "
                                + NumberFormat.getCurrencyInstance(Locale.FRANCE)
                                        .format(inv.amount());
            }
            url = "/?facture=" + inv.rowId(); // la liste s'ouvre sur cette facture
            tag = "facture-" + inv.rowId();
        } else {
            title = invoices.size() + " nouvelles factures";
            body = "Ouvre Conso maison pour les consulter";
            url = "/?vue=factures";
            tag = "factures-" + invoices.size() + "-" + invoices.get(0).rowId();
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("title", title);
        message.put("body", body);
        message.put("url", url);
        message.put("tag", tag);
        return mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
    }

    byte[] alertPayload(Alert a) {
        String energy = ENERGIES.getOrDefault(a.energy(), a.energy());
        String month =
                a.month().getMonth().getDisplayName(TextStyle.FULL, Locale.FRANCE)
                        + " "
                        + a.month().getYear();
        NumberFormat kwh = NumberFormat.getIntegerInstance(Locale.FRANCE);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("title", "Consommation de " + energy + " en hausse");
        message.put(
                "body",
                month
                        + " : "
                        + kwh.format(Math.round(a.quantity()))
                        + " kWh, +"
                        + a.changePct()
                        + " % par rapport à "
                        + a.month().minusYears(1).getYear());
        message.put("url", "/?energie=" + a.energy());
        message.put("tag", "alerte-" + a.energy() + "-" + a.month());
        return mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
    }
}
