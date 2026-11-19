package fr.byj6n.conso.push;

import static org.assertj.core.api.Assertions.assertThat;

import fr.byj6n.conso.model.Invoice;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PushNotifierTest {

    private final PushNotifier notifier = new PushNotifier(null, null, new ObjectMapper());

    private static Invoice invoice(String provider, String energy, Double amount) {
        return new Invoice(
                provider, energy, "N1", LocalDate.of(2026, 11, 2), amount, 0.0, null, "x", null);
    }

    private String payload(List<Invoice> invoices) {
        return new String(notifier.payload(invoices), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void uneFactureDonneFournisseurEnergieEtMontant() {
        String json = payload(List.of(invoice("ileo", "water", 295.87)));

        assertThat(json).contains("Nouvelle facture Iléo").contains("eau").contains("295,87");
    }

    @Test
    void plusieursFacturesDonnentUnCompteSansDetail() {
        String json = payload(List.of(invoice("ileo", "water", 1.0), invoice("gdb", "gas", 2.0)));

        assertThat(json).contains("2 nouvelles factures").doesNotContain("1,00");
    }

    @Test
    void sansMontantOnNAffichePasDeMontant() {
        String json = payload(List.of(invoice("gdb", "electricity", null)));

        assertThat(json)
                .contains("Nouvelle facture Gaz de Bordeaux")
                .contains("électricité")
                .doesNotContain("€");
    }

    @Test
    void desactiveNeFaitRien() throws Exception {
        assertThat(notifier.enabled()).isFalse();
        notifier.notifyNewInvoices(
                List.of(invoice("gdb", "gas", 1.0))); // ne lève rien, ne contacte rien
    }

    @Test
    void uneFactureOuvreLaListeSurCetteFacture() {
        String json = payload(List.of(invoice("ileo", "water", 10.0)));

        assertThat(json)
                .contains("\"url\":\"/?facture=ileo-N1\"")
                .contains("\"tag\":\"facture-ileo-N1\"");
    }

    @Test
    void plusieursFacturesOuvrentLaListe() {
        String json = payload(List.of(invoice("ileo", "water", 1.0), invoice("gdb", "gas", 2.0)));

        assertThat(json).contains("\"url\":\"/?vue=factures\"");
    }

    @Test
    void alerteDeHausseDonneLeMoisLePourcentageEtOuvreLeGraphique() {
        var alert =
                new fr.byj6n.conso.alert.Alert(
                        "gas", java.time.YearMonth.of(2026, 10), 450.4, 300, 50);

        String json =
                new String(notifier.alertPayload(alert), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(json)
                .contains("Consommation de gaz en hausse")
                .contains("octobre 2026")
                .contains("+50 %")
                .contains("2025")
                .contains("\"url\":\"/?energie=gas\"")
                .contains("\"tag\":\"alerte-gas-2026-10\"");
    }
}
