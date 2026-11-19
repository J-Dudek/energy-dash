package fr.byj6n.conso;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Un secret ne doit jamais sortir dans un log ou une exception qui afficherait la configuration.
 */
class CollectorPropertiesTest {

    @Test
    void identifiantsFournisseurMasques() {
        var a = new CollectorProperties.Account(true, "moi@example.com", "mot-de-passe-secret");

        assertThat(a.toString())
                .doesNotContain("mot-de-passe-secret", "moi@example.com")
                .contains("password=***");
    }

    @Test
    void netatmoMasque() {
        var n =
                new CollectorProperties.Netatmo(
                        true,
                        "client-id-123",
                        "client-secret-xyz",
                        "refresh-abc",
                        "data/t.json",
                        "sum_boiler_on",
                        1000,
                        null,
                        null);

        assertThat(n.toString())
                .doesNotContain("client-id-123", "client-secret-xyz", "refresh-abc");
    }

    @Test
    void cleApiAppwriteMasquee() {
        var a =
                new CollectorProperties.Appwrite(
                        "https://a/v1", "proj", "cle-api-secrete", "conso", "invoices", "users");

        assertThat(a.toString()).doesNotContain("cle-api-secrete").contains("proj");
    }

    @Test
    void valeurVideSignalee() {
        assertThat(new CollectorProperties.Account(false, null, "").toString())
                .contains("password=(vide)");
    }
}
