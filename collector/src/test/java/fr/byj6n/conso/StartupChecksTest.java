package fr.byj6n.conso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupChecksTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://appwrite.byj6n.fr/v1",
            "http://localhost:8080/v1", "http://127.0.0.1/v1",
            "http://192.168.1.10:8080/v1", "http://10.0.0.5/v1", "http://172.20.0.2/v1",
            "http://appwrite/v1", "http://nas.local/v1"})
    void endpointsAcceptes(String endpoint) {
        assertThatCode(() -> StartupChecks.requireSecureEndpoint(endpoint)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://appwrite.byj6n.fr/v1",   // clé API en clair sur Internet
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",          // juste hors de 172.16.0.0/12
            "ftp://appwrite.byj6n.fr/v1",
            "appwrite.byj6n.fr/v1",          // sans schéma
            ""})
    void endpointsRefuses(String endpoint) {
        assertThatThrownBy(() -> StartupChecks.requireSecureEndpoint(endpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONSO_APPWRITE_ENDPOINT");
    }

    @Test
    void endpointAbsent() {
        assertThatThrownBy(() -> StartupChecks.requireSecureEndpoint(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reconnaitLesHotesLocaux() {
        assertThat(StartupChecks.isLocalHost("example.com")).isFalse();
        assertThat(StartupChecks.isLocalHost("192.169.0.1")).isFalse();
        assertThat(StartupChecks.isLocalHost("192.168.0.1")).isTrue();
    }
}
