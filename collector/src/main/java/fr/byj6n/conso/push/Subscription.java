package fr.byj6n.conso.push;

/** Abonnement Web Push d'un appareil (ligne de la table {@code push_subscriptions}). */
public record Subscription(String id, String endpoint, String p256dh, String auth) {

    /** Les clés sont propres à l'appareil et servent à chiffrer : pas dans les journaux. */
    @Override
    public String toString() {
        return "Subscription[id=" + id + "]";
    }
}
