package fr.byj6n.conso.netatmo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetatmoTokenStoreTest {

    @TempDir
    Path dir;

    private NetatmoTokenStore store() {
        return new NetatmoTokenStore(dir.resolve("data/netatmo-token.json"), new ObjectMapper());
    }

    @Test
    void premierUsageSeFaitAvecLeJetonDuEnv() throws Exception {
        assertThat(store().load("seed-1")).isEqualTo("seed-1");
    }

    @Test
    void lesRenouvellementsSuccessifsSontConserves() throws Exception {
        store().save("seed-1", "rotated-1");

        assertThat(store().load("seed-1")).isEqualTo("rotated-1"); // le .env n'a pas changé : on suit la rotation
        assertThat(store().load("")).isEqualTo("rotated-1");       // le .env peut même être vidé
    }

    @Test
    void unNouveauJetonDansLeEnvPrendLePasSurLeFichier() throws Exception {
        store().save("seed-1", "rotated-1");

        assertThat(store().load("seed-2")).isEqualTo("seed-2");
    }

    @Test
    void sansAucunJetonOnPrevientClairement() {
        assertThatThrownBy(() -> store().load(""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CONSO_NETATMO_REFRESH_TOKEN");
    }
}
