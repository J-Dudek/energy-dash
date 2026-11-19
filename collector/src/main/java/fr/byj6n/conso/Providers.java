package fr.byj6n.conso;

import fr.byj6n.conso.port.ProviderClient;

import java.util.List;

/** Fournisseurs activés. Un holder explicite évite l'injection ambiguë d'une List<ProviderClient>. */
public record Providers(List<ProviderClient> clients) {
}
