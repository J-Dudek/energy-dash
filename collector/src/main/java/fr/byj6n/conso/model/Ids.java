package fr.byj6n.conso.model;

/** Identifiants de lignes Appwrite : ≤ 36 caractères parmi [a-zA-Z0-9._-], sans début spécial. */
final class Ids {

    static final int MAX = 36;

    private Ids() {}

    /**
     * Les valeurs viennent des fournisseurs : on ne leur fait pas confiance pour la forme. Un
     * identifiant trop long est raccourci avec une empreinte, pour rester déterministe (upsert)
     * sans collision pratique.
     */
    static String of(String raw) {
        String id = raw.replaceAll("[^a-zA-Z0-9._-]", "").replaceAll("^[^a-zA-Z0-9]+", "");
        if (id.isEmpty()) {
            id = "x" + Integer.toHexString(raw.hashCode());
        }
        if (id.length() <= MAX) return id;
        return id.substring(0, MAX - 9) + "-" + String.format("%08x", id.hashCode());
    }
}
