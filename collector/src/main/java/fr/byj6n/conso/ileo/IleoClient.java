package fr.byj6n.conso.ileo;

import fr.byj6n.conso.CollectorProperties;
import fr.byj6n.conso.http.Bodies;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.port.ProviderClient;
import fr.byj6n.conso.port.ProviderSession;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client de l'espace perso Iléo (eau de la MEL). Pas d'API : formulaire de connexion
 * classique + pages HTML, session portée par cookie.
 * <ul>
 *   <li>POST /connexion.aspx email, password, valider, connexion=1</li>
 *   <li>GET /espaceperso/mes-consommations-facturees.aspx, /espaceperso/gerer-factures.aspx</li>
 *   <li>GET /common/pdfReader.aspx?cleDoc=... → PDF</li>
 * </ul>
 */
public class IleoClient implements ProviderClient {

    private static final String BASE = "https://www.mel-ileo.fr";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    private final CollectorProperties.Account account;

    public IleoClient(CollectorProperties.Account account) {
        this.account = account;
    }

    @Override
    public String provider() {
        return IleoParser.PROVIDER;
    }

    @Override
    public ProviderSession connect() throws IOException, InterruptedException {
        // Un client HTTP (donc un jeu de cookies) par session.
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        get(http, "/connexion.aspx"); // cookie de session ASP.NET

        Map<String, String> form = new LinkedHashMap<>();
        form.put("email", account.email());
        form.put("password", account.password());
        form.put("valider", "je me connecte");
        form.put("connexion", "1");
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> resp = http.send(req("/connexion.aspx")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", BASE)
                .header("Referer", BASE + "/connexion.aspx")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        requireIleoHost(resp);
        if (resp.statusCode() >= 400) {
            throw new IOException("Iléo : échec de connexion (HTTP " + resp.statusCode() + ")");
        }
        String check = get(http, "/espaceperso/gerer-factures.aspx");
        if (IleoParser.isLoginPage(check)) {
            throw new IOException("Iléo : identifiants refusés (retour sur la page de connexion)");
        }
        return new Session(http);
    }

    /** Une collecte : les cookies de session et les deux pages HTML, lues une seule fois. */
    private static final class Session implements ProviderSession {

        private final HttpClient http;
        private List<IleoParser.Row> rows;

        Session(HttpClient http) {
            this.http = http;
        }

        @Override
        public List<Reading> readings() throws IOException, InterruptedException {
            return IleoParser.readings(rows());
        }

        @Override
        public List<Invoice> invoices() throws IOException, InterruptedException {
            return IleoParser.invoices(rows());
        }

        @Override
        public byte[] downloadPdf(Invoice invoice) throws IOException, InterruptedException {
            if (invoice.sourceRef() == null) {
                throw new IOException("Iléo : pas de lien PDF pour la facture " + invoice.number());
            }
            HttpResponse<java.io.InputStream> resp = http.send(req("/common/pdfReader.aspx?cleDoc=" + enc(invoice.sourceRef()))
                    .header("Referer", BASE + "/espaceperso/gerer-factures.aspx")
                    .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            requireIleoHost(resp);
            byte[] pdf = resp.statusCode() == 200 ? Bodies.readAtMost(resp.body(), Bodies.MAX_PDF_BYTES) : new byte[0];
            if (resp.statusCode() != 200 || pdf.length < 5 || !new String(pdf, 0, 5).equals("%PDF-")) {
                throw new IOException("Iléo : téléchargement de la facture " + invoice.number()
                        + " impossible (HTTP " + resp.statusCode() + ")");
            }
            return pdf;
        }

        private List<IleoParser.Row> rows() throws IOException, InterruptedException {
            if (rows == null) {
                String conso = get(http, "/espaceperso/mes-consommations-facturees.aspx");
                String factures = get(http, "/espaceperso/gerer-factures.aspx");
                if (IleoParser.isLoginPage(conso)) {
                    throw new IOException("Iléo : session perdue");
                }
                rows = IleoParser.parse(conso, factures);
            }
            return rows;
        }
    }

    private static String get(HttpClient http, String path) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        requireIleoHost(resp);
        if (resp.statusCode() != 200) {
            throw new IOException("Iléo : GET " + path + " → HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /**
     * Les redirections sont suivies (l'ASP.NET en fait à la connexion), mais jamais vers un autre hôte :
     * on refuse de poursuivre une session, cookies compris, ailleurs que chez Iléo.
     */
    private static void requireIleoHost(HttpResponse<?> resp) throws IOException {
        String host = resp.uri().getHost();
        if (host == null || !host.equals(URI.create(BASE).getHost())) {
            throw new IOException("Iléo : redirection inattendue vers un autre hôte, session abandonnée");
        }
    }

    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", UA)
                .header("Accept-Language", "fr-FR,fr;q=0.9");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
