package fr.byj6n.conso.netatmo;

import fr.byj6n.conso.model.Reading;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Convertit les réponses JSON de l'API Netatmo en modèle normalisé.
 *
 * <pre>
 * GET /api/homesdata
 *   { "body": { "homes": [ { "modules": [
 *       { "id": "70:ee:50:..", "type": "NAPlug" },
 *       { "id": "04:00:00:..", "type": "NATherm1", "bridge": "70:ee:50:.." } ] } ] } }
 * GET /api/getmeasure?...&optimize=false&real_time=true     (secondes de marche de la chaudière par jour)
 *   { "body": { "1763420400": [14820], "1763506800": [9300] }, "status": "ok" }
 * ou, sans optimize=false :
 *   { "body": [ { "beg_time": 1763420400, "step_time": 86400, "value": [[14820],[9300]] } ] }
 * </pre>
 */
final class NetatmoMapper {

    static final String PROVIDER = "netatmo";
    static final String ENERGY = "heating";
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private NetatmoMapper() {}

    /** Relais (device_id) et thermostat (module_id) à interroger. */
    record Thermostat(String deviceId, String moduleId) {}

    /** Premier thermostat NATherm1 relié à un relais. */
    static Optional<Thermostat> thermostat(JsonNode homesData) {
        for (JsonNode home : homesData.path("body").path("homes")) {
            for (JsonNode module : home.path("modules")) {
                if ("NATherm1".equals(module.path("type").asText())
                        && module.hasNonNull("bridge")) {
                    return Optional.of(
                            new Thermostat(
                                    module.get("bridge").asText(), module.path("id").asText()));
                }
            }
        }
        return Optional.empty();
    }

    /** Un relevé par jour, en heures de chauffe. Les valeurs absentes sont ignorées. */
    static List<Reading> heating(JsonNode getMeasure) {
        Map<LocalDate, Double> seconds = new TreeMap<>();
        JsonNode body = getMeasure.path("body");
        if (body.isArray()) {
            for (JsonNode chunk : body) {
                long begin = chunk.path("beg_time").asLong();
                long step = chunk.path("step_time").asLong(86_400);
                int i = 0;
                for (JsonNode v : chunk.path("value")) {
                    JsonNode first = v.isArray() ? v.path(0) : v;
                    if (first.isNumber())
                        seconds.merge(day(begin + i * step), first.asDouble(), Double::sum);
                    i++;
                }
            }
        } else {
            for (var e : body.properties()) {
                JsonNode first = e.getValue().path(0);
                if (first.isNumber())
                    seconds.merge(day(Long.parseLong(e.getKey())), first.asDouble(), Double::sum);
            }
        }
        List<Reading> out = new ArrayList<>();
        seconds.forEach(
                (date, s) ->
                        out.add(
                                new Reading(
                                        PROVIDER,
                                        ENERGY,
                                        "day",
                                        date,
                                        date,
                                        Math.round(s / 3600.0 * 100) / 100.0,
                                        "h",
                                        null,
                                        null,
                                        null)));
        return out;
    }

    private static LocalDate day(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZONE).toLocalDate();
    }
}
