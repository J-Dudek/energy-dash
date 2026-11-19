package fr.byj6n.conso;

import fr.byj6n.conso.port.ProviderClient;
import java.util.List;

/**
 * Fournisseurs activés. Un holder explicite évite l'injection ambiguë d'une List<ProviderClient>.
 */
public record Providers(List<ProviderClient> clients) {

    /** Copie immuable : la liste de fournisseurs ne change plus une fois construite. */
    public Providers {
        clients = List.copyOf(clients);
    }
}
