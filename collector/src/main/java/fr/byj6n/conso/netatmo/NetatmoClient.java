package fr.byj6n.conso.netatmo;

import fr.byj6n.conso.CollectorProperties;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.port.ProviderClient;
import fr.byj6n.conso.port.ProviderSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Temps de chauffe de la chaudière, via l'API officielle Netatmo (thermostat + relais).
 * <ul>
 *   <li>POST /oauth2/token (refresh_token) → access_token (3 h) + nouveau refresh_token, à conserver</li>
 *   <li>GET  /api/homesdata → relais (NAPlug) et thermostat (NATherm1)</li>
 *   <li>GET  /api/getmeasure (scale=1day, type=sum_boiler_on) → secondes de marche par jour</li>
 * </ul>
 * Pas de facture : un relevé journalier "heating" en heures, à rapprocher de la consommation de gaz.
 */
public class NetatmoClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(NetatmoClient.class);
    private static final String BASE = "https://api.netatmo.com";
    private static final double SECONDS_PER_DAY = 86_400;
    /** getmeasure renvoie au plus 1024 valeurs par appel : au pas d'un jour, on reste en dessous. */
    private static final int MAX_LOOKBACK_DAYS = 1000;

    private final CollectorProperties.Netatmo cfg;
    private final ObjectMapper mapper;
    private final String base;
    private final NetatmoTokenStore tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public NetatmoClient(CollectorProperties.Netatmo cfg, ObjectMapper mapper) {
        this(cfg, mapper, BASE);
    }

    NetatmoClient(CollectorProperties.Netatmo cfg, ObjectMapper mapper, String base) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.base = base;
        this.tokens = new NetatmoTokenStore(Path.of(cfg.tokenFile()), mapper);
    }

    @Override
    public String provider() {
        return NetatmoMapper.PROVIDER;
    }

    @Override
    public ProviderSession connect() throws IOException, InterruptedException {
        String accessToken = refreshAccessToken();
        return new Session(accessToken);
    }

    /** Échange le jeton de renouvellement et enregistre aussitôt le nouveau : l'ancien est déjà périmé. */
    private String refreshAccessToken() throws IOException, InterruptedException {
        String refresh = tokens.load(cfg.refreshToken());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refresh);
        form.put("client_id", cfg.clientId());
        form.put("client_secret", cfg.clientSecret());
        HttpResponse<String> resp = http.send(request("/oauth2/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form))).build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            String error = mapper.readTree(resp.body()).path("error").asText("?");
            throw new IOException("Netatmo : renouvellement du jeton refusé (HTTP " + resp.statusCode() + ", " + error
                    + "). Génère un nouveau jeton sur dev.netatmo.com et mets-le dans CONSO_NETATMO_REFRESH_TOKEN.");
        }
        JsonNode json = mapper.readTree(resp.body());
        String access = json.path("access_token").asText(null);
        String next = json.path("refresh_token").asText(null);
        if (access == null || next == null) {
            throw new IOException("Netatmo : réponse de jeton incomplète");
        }
        tokens.save(cfg.refreshToken(), next);
        return access;
    }

    private final class Session implements ProviderSession {

        private final String accessToken;

        Session(String accessToken) {
            this.accessToken = accessToken;
        }

        @Override
        public List<Reading> readings() throws IOException, InterruptedException {
            NetatmoMapper.Thermostat t = thermostat();
            long end = Instant.now().getEpochSecond();
            int days = Math.min(cfg.lookbackDays(), MAX_LOOKBACK_DAYS);
            if (days < cfg.lookbackDays()) {
                log.warn("[netatmo] historique limité à {} jours (au lieu de {}) : Netatmo plafonne à 1024 valeurs par appel",
                        days, cfg.lookbackDays());
            }
            long begin = end - days * 86_400L;
            Map<String, String> q = new LinkedHashMap<>();
            q.put("device_id", t.deviceId());
            q.put("module_id", t.moduleId());
            q.put("scale", "1day");
            q.put("type", cfg.measureType());
            q.put("date_begin", String.valueOf(begin));
            q.put("date_end", String.valueOf(end));
            q.put("real_time", "true");
            q.put("optimize", "false");
            List<Reading> readings = NetatmoMapper.heating(getJson("/api/getmeasure?" + encode(q)));

            double max = readings.stream().mapToDouble(Reading::quantity).max().orElse(0);
            if (max * 3600 > SECONDS_PER_DAY * 1.05) {
                log.warn("[netatmo] {} h de chauffe sur une journée : l'unité de '{}' n'est sans doute pas la seconde",
                        max, cfg.measureType());
            }
            log.info("[netatmo] {} jours de chauffe reçus ({}), max {} h/jour", readings.size(), cfg.measureType(), max);
            return readings;
        }

        @Override
        public List<Invoice> invoices() {
            return List.of();
        }

        @Override
        public byte[] downloadPdf(Invoice invoice) throws IOException {
            throw new IOException("Netatmo : pas de facture");
        }

        private NetatmoMapper.Thermostat thermostat() throws IOException, InterruptedException {
            if (notBlank(cfg.deviceId()) && notBlank(cfg.moduleId())) {
                return new NetatmoMapper.Thermostat(cfg.deviceId(), cfg.moduleId());
            }
            return NetatmoMapper.thermostat(getJson("/api/homesdata"))
                    .orElseThrow(() -> new IOException("Netatmo : aucun thermostat NATherm1 trouvé sur le compte"));
        }

        private JsonNode getJson(String path) throws IOException, InterruptedException {
            HttpResponse<String> resp = http.send(request(path)
                    .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Netatmo : GET " + path.split("\\?")[0] + " → HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
