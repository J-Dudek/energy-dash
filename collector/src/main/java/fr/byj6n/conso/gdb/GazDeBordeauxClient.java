package fr.byj6n.conso.gdb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import fr.byj6n.conso.CollectorProperties;
import fr.byj6n.conso.http.Bodies;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import fr.byj6n.conso.port.ProviderClient;
import fr.byj6n.conso.port.ProviderSession;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client de l'espace client Gaz de Bordeaux (API JSON interne de la SPA Nuxt).
 * <ul>
 *   <li>POST /api/authentication-token {email,password} → {token, lifeToken} (JWT valable 1 h)</li>
 *   <li>GET  /api/me → contrats (elec / gaz)</li>
 *   <li>GET  /api/contract/{elec|gas}/{contractId}/consumption → 12-13 derniers mois</li>
 *   <li>GET  /api/contracts/{contractId} → détail du contrat, dont la grille tarifaire (tarifScale)</li>
 *   <li>GET  /api/documents/{contractId} → factures</li>
 *   <li>POST /api/document/download {documentId, documentType, contractId} → PDF brut</li>
 * </ul>
 * API non officielle : elle peut changer sans préavis.
 */
public class GazDeBordeauxClient implements ProviderClient {

    private static final String BASE = "https://espaceclient.gazdebordeaux.fr";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    private final CollectorProperties.Account account;
    private final ObjectMapper mapper;
    private final HttpClient http; // thread-safe, sans état de session

    public GazDeBordeauxClient(CollectorProperties.Account account, ObjectMapper mapper) {
        this.account = account;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER) // le mot de passe est envoyé en POST : jamais rejoué ailleurs
                .build();
    }

    @Override
    public String provider() {
        return GazDeBordeauxMapper.PROVIDER;
    }

    @Override
    public ProviderSession connect() throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of("email", account.email(), "password", account.password()));
        HttpResponse<String> resp = http.send(base("/api/authentication-token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        // L'API répond 201 (Created) à la création du token ; on accepte tout 2xx.
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Gaz de Bordeaux : échec de connexion (HTTP " + resp.statusCode() + ")");
        }
        String token = mapper.readTree(resp.body()).path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IOException("Gaz de Bordeaux : pas de token dans la réponse de connexion");
        }
        Session session = new Session(token);
        session.loadContracts();
        return session;
    }

    /** Une collecte : le jeton JWT (valable 1 h) et les contrats du compte. */
    private final class Session implements ProviderSession {

        private final String token;
        private final Map<Long, String> contracts = new LinkedHashMap<>(); // id -> type ("elec"/"gaz")

        Session(String token) {
            this.token = token;
        }

        void loadContracts() throws IOException, InterruptedException {
            for (JsonNode c : getJson("/api/me").path("contracts")) {
                contracts.put(c.path("id").asLong(), c.path("type").asText());
            }
        }

        @Override
        public List<Reading> readings() throws IOException, InterruptedException {
            List<Reading> out = new ArrayList<>();
            for (var c : contracts.entrySet()) {
                String segment = GazDeBordeauxMapper.apiSegment(c.getValue());
                out.addAll(GazDeBordeauxMapper.readings(segment,
                        getJson("/api/contract/" + segment + "/" + c.getKey() + "/consumption")));
            }
            return out;
        }

        @Override
        public List<Invoice> invoices() throws IOException, InterruptedException {
            Map<String, Invoice> byNumber = new LinkedHashMap<>(); // offre duale : une facture peut remonter sur les 2 contrats
            for (Long id : contracts.keySet()) {
                for (Invoice inv : GazDeBordeauxMapper.invoices(id, getJson("/api/documents/" + id))) {
                    byNumber.putIfAbsent(inv.number(), inv);
                }
            }
            return new ArrayList<>(byNumber.values());
        }

        @Override
        public List<Tariff> tariffs() throws IOException, InterruptedException {
            List<Tariff> out = new ArrayList<>();
            for (var c : contracts.entrySet()) {
                Tariff t = GazDeBordeauxMapper.tariff(c.getValue(), getJson("/api/contracts/" + c.getKey()));
                if (t != null) out.add(t);
            }
            return out;
        }

        @Override
        public byte[] downloadPdf(Invoice invoice) throws IOException, InterruptedException {
            String[] ref = invoice.sourceRef().split("\\|", 2); // "type|documentId"
            String body = mapper.writeValueAsString(Map.of(
                    "documentId", ref[1],
                    "documentType", ref[0],
                    "contractId", invoice.contractId()));
            HttpResponse<java.io.InputStream> resp = http.send(authed("/api/document/download")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] pdf = resp.statusCode() == 200 ? Bodies.readAtMost(resp.body(), Bodies.MAX_PDF_BYTES) : new byte[0];
            if (resp.statusCode() != 200 || pdf.length < 5 || !new String(pdf, 0, 5).equals("%PDF-")) {
                throw new IOException("Gaz de Bordeaux : téléchargement de la facture " + invoice.number()
                        + " impossible (HTTP " + resp.statusCode() + ")");
            }
            return pdf;
        }

        private JsonNode getJson(String path) throws IOException, InterruptedException {
            HttpResponse<String> resp = http.send(authed(path).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Gaz de Bordeaux : GET " + path.replaceAll("\\d{5,}", "{id}") + " → HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        }

        private HttpRequest.Builder authed(String path) {
            return base(path).header("Authorization", "Bearer " + token);
        }
    }

    private static HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("Origin", BASE)
                .header("Referer", BASE + "/");
    }
}
