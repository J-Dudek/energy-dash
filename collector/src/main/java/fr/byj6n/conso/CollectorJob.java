package fr.byj6n.conso;

import fr.byj6n.conso.appwrite.AppwriteSchema;
import fr.byj6n.conso.appwrite.AppwriteStore;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import fr.byj6n.conso.port.ProviderClient;
import fr.byj6n.conso.port.ProviderSession;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collecte planifiée : pour chaque fournisseur, connexion puis relevés, factures (+ PDF manquants)
 * et tarifs. Un fournisseur en erreur n'empêche pas les autres de passer.
 */
@Component
public class CollectorJob {

    private static final Logger log = LoggerFactory.getLogger(CollectorJob.class);

    private final List<ProviderClient> clients;
    private final AppwriteSchema schema;
    private final AppwriteStore store;
    private final CollectorProperties props;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean schemaReady;

    public CollectorJob(
            Providers providers,
            AppwriteSchema schema,
            AppwriteStore store,
            CollectorProperties props) {
        this.clients = providers.clients();
        this.schema = schema;
        this.store = store;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (clients.isEmpty()) {
            log.warn("Aucun fournisseur activé (CONSO_GDB_ENABLED / CONSO_ILEO_ENABLED)");
        }
        if (props.runOnStartup()) {
            run();
        }
    }

    @Scheduled(cron = "${conso.cron:0 17 6 * * *}", zone = "Europe/Paris")
    public void run() {
        if (!lock.tryLock()) {
            log.info("Collecte déjà en cours, on saute ce créneau");
            return;
        }
        try {
            if (!schemaReady) {
                schema.ensure();
                schemaReady = true;
            }
            for (ProviderClient client : clients) {
                collect(client);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Collecte interrompue : {}", e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private void collect(ProviderClient client) throws InterruptedException {
        String p = client.provider();
        try {
            ProviderSession session = client.connect();
            int readings = collectReadings(session);
            InvoiceStats invoices = collectInvoices(p, session);
            int tariffs = collectTariffs(p, session);
            log.info(
                    "[{}] {} relevés, {} factures ({} nouveaux PDF), {} tarifs",
                    p,
                    readings,
                    invoices.total(),
                    invoices.newPdf(),
                    tariffs);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] collecte en échec : {}", p, e.getMessage());
            log.debug("[{}] détail", p, e);
        }
    }

    private int collectReadings(ProviderSession session) throws IOException, InterruptedException {
        List<Reading> readings = session.readings();
        for (Reading r : readings) {
            store.upsert(r);
        }
        return readings.size();
    }

    /** Une facture dont le PDF est inaccessible est ignorée sans bloquer les suivantes. */
    private InvoiceStats collectInvoices(String provider, ProviderSession session)
            throws IOException, InterruptedException {
        List<Invoice> invoices = session.invoices();
        int newPdf = 0;
        for (Invoice inv : invoices) {
            try {
                if (store.upsert(inv, () -> session.downloadPdf(inv))) newPdf++;
            } catch (IOException e) {
                log.warn("[{}] facture {} ignorée : {}", provider, inv.number(), e.getMessage());
            }
        }
        return new InvoiceStats(invoices.size(), newPdf);
    }

    /**
     * Secondaire : une erreur est journalisée mais ne fait pas échouer la collecte du fournisseur.
     */
    private int collectTariffs(String provider, ProviderSession session)
            throws InterruptedException {
        try {
            List<Tariff> tariffs = session.tariffs();
            for (Tariff t : tariffs) {
                store.upsert(t);
            }
            return tariffs.size();
        } catch (IOException e) {
            log.warn("[{}] tarifs ignorés : {}", provider, e.getMessage());
            return 0;
        }
    }

    private record InvoiceStats(int total, int newPdf) {}
}
