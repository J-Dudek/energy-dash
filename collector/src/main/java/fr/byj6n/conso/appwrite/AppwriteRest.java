package fr.byj6n.conso.appwrite;

import fr.byj6n.conso.CollectorProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Accès minimal à l'API REST Appwrite (TablesDB + Storage) avec une clé API serveur. On évite le
 * SDK Kotlin (coroutines) : quelques appels HTTP suffisent.
 */
public class AppwriteRest {

    /** Réponse HTTP brute : le code est exposé pour gérer 404 / 409 côté appelant. */
    public record Result(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final CollectorProperties.Appwrite cfg;
    private final ObjectMapper mapper;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AppwriteRest(CollectorProperties.Appwrite cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
    }

    public Result get(String path) throws IOException, InterruptedException {
        return send(base(path).GET());
    }

    public Result post(String path, Object body) throws IOException, InterruptedException {
        return send(json(base(path), "POST", body));
    }

    public Result put(String path, Object body) throws IOException, InterruptedException {
        return send(json(base(path), "PUT", body));
    }

    /** Envoie un fichier dans un bucket (≤ 5 Mo : pas besoin d'upload par morceaux). */
    public Result uploadFile(String bucketId, String fileId, String filename, byte[] content)
            throws IOException, InterruptedException {
        String boundary = "----conso" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePart(
                out, boundary, "Content-Disposition: form-data; name=\"fileId\"\r\n\r\n" + fileId);
        // le nom vient d'un fournisseur : pas de guillemet ni de saut de ligne dans un en-tête
        // multipart
        String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        writePart(
                out,
                boundary,
                "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + safeName
                        + "\"\r\nContent-Type: application/pdf\r\n\r\n");
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(
                base("/storage/buckets/" + bucketId + "/files")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
    }

    /** Lève une exception explicite si la réponse n'est pas 2xx. */
    public static Result check(Result r, String what) throws IOException {
        if (!r.ok()) {
            String msg = r.body() != null ? r.body().path("message").asText("") : "";
            throw new IOException("Appwrite : " + what + " → HTTP " + r.status() + " " + msg);
        }
        return r;
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String head)
            throws IOException {
        out.write(("--" + boundary + "\r\n" + head).getBytes(StandardCharsets.UTF_8));
        if (!head.endsWith("\r\n\r\n")) {
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private HttpRequest.Builder json(HttpRequest.Builder b, String method, Object body)
            throws IOException {
        return b.header("Content-Type", "application/json")
                .method(
                        method,
                        HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    }

    private HttpRequest.Builder base(String path) {
        String endpoint = cfg.endpoint().replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(60))
                .header("X-Appwrite-Project", cfg.projectId())
                .header("X-Appwrite-Key", cfg.apiKey())
                .header("Accept", "application/json");
    }

    private Result send(HttpRequest.Builder b) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body =
                resp.body() == null || resp.body().isBlank() ? null : mapper.readTree(resp.body());
        return new Result(resp.statusCode(), body);
    }
}
