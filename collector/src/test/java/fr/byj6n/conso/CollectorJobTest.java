package fr.byj6n.conso;

import com.sun.net.httpserver.HttpServer;
import fr.byj6n.conso.appwrite.AppwriteRest;
import fr.byj6n.conso.appwrite.AppwriteSchema;
import fr.byj6n.conso.appwrite.AppwriteStore;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import fr.byj6n.conso.port.ProviderClient;
import fr.byj6n.conso.port.ProviderSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration de la collecte, contre un faux Appwrite local et des fournisseurs factices :
 * ce qui est écrit, et surtout ce qui n'empêche pas le reste de passer.
 */
class CollectorJobTest {

    private static final String API = "/v1/tablesdb/conso/tables/";

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>(); // "METHODE chemin"
    private AppwriteSchema schema;
    private AppwriteStore store;
    private CollectorProperties props;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", ex -> {
            ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            calls.add(ex.getRequestMethod() + " " + path);
            // aucun PDF n'est encore dans le bucket ; tout le reste répond OK
            int status = ex.getRequestMethod().equals("GET") && path.contains("/files/") ? 404 : 200;
            byte[] out = "{}".getBytes();
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        var cfg = new CollectorProperties.Appwrite("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "proj", "secret-key", "conso", "invoices", "users");
        var rest = new AppwriteRest(cfg, new ObjectMapper());
        schema = new AppwriteSchema(rest, cfg);
        store = new AppwriteStore(rest, cfg);
        props = new CollectorProperties(false, null, null, null, cfg);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void ecritRelevesFacturesTarifsEtPdf() {
        var fake = new FakeProvider("ileo").withData();

        job(fake).run();

        assertThat(calls).contains(
                "PUT " + API + "readings/rows/ileo-water-2026-06-30",
                "PUT " + API + "invoices/rows/ileo-F1",
                "PUT " + API + "tariffs/rows/ileo-water-2026-01-01",
                "POST /v1/storage/buckets/invoices/files");
    }

    @Test
    void fournisseurEnPanneNeBloquePasLesAutres() {
        var panne = new FakeProvider("gdb");
        panne.connectError = new IOException("identifiants refusés");
        var ok = new FakeProvider("ileo").withData();

        job(panne, ok).run();

        assertThat(ok.connects).hasValue(1);
        assertThat(calls).contains("PUT " + API + "invoices/rows/ileo-F1");
        assertThat(calls).noneMatch(c -> c.contains("gdb-"));
    }

    @Test
    void erreurSurLesTarifsNeBloquePasRelevesEtFactures() {
        var fake = new FakeProvider("ileo").withData();
        fake.tariffsError = new IOException("endpoint tarifs indisponible");

        job(fake).run();

        assertThat(calls).contains(
                "PUT " + API + "readings/rows/ileo-water-2026-06-30",
                "PUT " + API + "invoices/rows/ileo-F1");
        assertThat(calls).noneMatch(c -> c.contains("/tariffs/rows/"));
    }

    @Test
    void pdfInaccessibleIgnoreLaFactureEtPoursuit() {
        var fake = new FakeProvider("ileo").withData();
        fake.invoices = List.of(invoice("F1"), invoice("F2"));
        fake.failingPdfs = Set.of("F1");

        job(fake).run();

        assertThat(calls).contains("PUT " + API + "invoices/rows/ileo-F2");
        assertThat(calls).doesNotContain("PUT " + API + "invoices/rows/ileo-F1");
        assertThat(calls).contains("PUT " + API + "tariffs/rows/ileo-water-2026-01-01"); // l'étape suivante a bien eu lieu
    }

    @Test
    void schemaCreeUneSeuleFois() {
        var job = job(new FakeProvider("ileo").withData());

        job.run();
        job.run();

        assertThat(calls.stream().filter(c -> c.equals("POST /v1/tablesdb")).count()).isEqualTo(1);
    }

    @Test
    void collecteDejaEnCoursEstSautee() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slow = new FakeProvider("ileo").withData();
        slow.beforeReadings = () -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
        };
        var job = job(slow);

        Thread first = new Thread(job::run);
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        job.run(); // la première collecte tient encore le verrou
        release.countDown();
        first.join(5_000);

        assertThat(slow.connects).hasValue(1);
    }

    private CollectorJob job(FakeProvider... providers) {
        return new CollectorJob(new Providers(List.<ProviderClient>of(providers)), schema, store, props);
    }

    private static Invoice invoice(String number) {
        return new Invoice("ileo", "water", number, LocalDate.of(2026, 6, 2), 100.0, 0.0, 10.0, "ref-" + number, null);
    }

    @FunctionalInterface
    interface Blocking {
        void run() throws InterruptedException;
    }

    /** Fournisseur factice, configurable : données à renvoyer et erreurs à provoquer. */
    static class FakeProvider implements ProviderClient, ProviderSession {

        final String id;
        final AtomicInteger connects = new AtomicInteger();
        IOException connectError;
        IOException tariffsError;
        Set<String> failingPdfs = Set.of();
        Blocking beforeReadings = () -> {
        };
        List<Reading> readings = List.of();
        List<Invoice> invoices = List.of();
        List<Tariff> tariffs = List.of();

        FakeProvider(String id) {
            this.id = id;
        }

        FakeProvider withData() {
            readings = List.of(new Reading(id, "water", "invoice", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                    64, "m3", null, null, null));
            invoices = List.of(invoice("F1"));
            tariffs = List.of(new Tariff(id, "water", LocalDate.of(2026, 1, 1), "OFFRE", 1.0, null, null, null));
            return this;
        }

        @Override
        public String provider() {
            return id;
        }

        @Override
        public ProviderSession connect() throws IOException {
            connects.incrementAndGet();
            if (connectError != null) throw connectError;
            return this;
        }

        @Override
        public List<Reading> readings() throws InterruptedException {
            beforeReadings.run();
            return readings;
        }

        @Override
        public List<Invoice> invoices() {
            return invoices;
        }

        @Override
        public List<Tariff> tariffs() throws IOException {
            if (tariffsError != null) throw tariffsError;
            return tariffs;
        }

        @Override
        public byte[] downloadPdf(Invoice invoice) throws IOException {
            if (failingPdfs.contains(invoice.number())) throw new IOException("PDF indisponible");
            return "%PDF-1.7 fake".getBytes();
        }
    }
}
