package fr.byj6n.conso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration du collecteur. Tout vient des variables d'environnement du conteneur
 * (CONSO_GDB_EMAIL, CONSO_APPWRITE_API_KEY, ...). Les identifiants fournisseurs ne sortent jamais
 * de ce process.
 */
@ConfigurationProperties(prefix = "conso")
public record CollectorProperties(
        @DefaultValue("true") boolean runOnStartup,
        Account gdb,
        Account ileo,
        Netatmo netatmo,
        Appwrite appwrite) {

    public record Account(@DefaultValue("false") boolean enabled, String email, String password) {

        /** Pas de secret ni d'adresse e-mail dans les logs ou les messages d'erreur. */
        @Override
        public String toString() {
            return "Account[enabled="
                    + enabled
                    + ", email="
                    + redact(email)
                    + ", password="
                    + redact(password)
                    + "]";
        }
    }

    /**
     * Thermostat Netatmo (temps de chauffe de la chaudière). Authentification OAuth2 : le jeton de
     * renouvellement tourne à chaque usage, il est donc conservé dans {@code tokenFile}.
     */
    public record Netatmo(
            @DefaultValue("false") boolean enabled,
            String clientId,
            String clientSecret,
            /* Jeton initial (générateur de jetons de dev.netatmo.com, droit read_thermostat). */
            String refreshToken,
            @DefaultValue("data/netatmo-token.json") String tokenFile,
            /* Type de mesure getmeasure pour le temps de marche de la chaudière, en secondes. */
            @DefaultValue("sum_boiler_on") String measureType,
            @DefaultValue("1000") int lookbackDays,
            /* Facultatif : sinon le premier thermostat NATherm1 du compte est utilisé. */
            String deviceId,
            String moduleId) {

        @Override
        public String toString() {
            return "Netatmo[enabled="
                    + enabled
                    + ", clientId="
                    + redact(clientId)
                    + ", clientSecret="
                    + redact(clientSecret)
                    + ", refreshToken="
                    + redact(refreshToken)
                    + ", measureType="
                    + measureType
                    + "]";
        }
    }

    public record Appwrite(
            String endpoint,
            String projectId,
            String apiKey,
            @DefaultValue("conso") String databaseId,
            @DefaultValue("invoices") String bucketId,
            /* Rôle Appwrite autorisé à lire les données, ex. "users" ou "user:<id>" ou "team:<id>". */
            @DefaultValue("users") String readRole) {

        @Override
        public String toString() {
            return "Appwrite[endpoint="
                    + endpoint
                    + ", projectId="
                    + projectId
                    + ", apiKey="
                    + redact(apiKey)
                    + ", databaseId="
                    + databaseId
                    + ", bucketId="
                    + bucketId
                    + ", readRole="
                    + readRole
                    + "]";
        }
    }

    /**
     * "***" pour une valeur renseignée, "(vide)" sinon : on sait si c'est configuré sans rien
     * révéler.
     */
    static String redact(String secret) {
        return secret == null || secret.isBlank() ? "(vide)" : "***";
    }
}
