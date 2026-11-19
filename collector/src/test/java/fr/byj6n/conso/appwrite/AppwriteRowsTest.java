package fr.byj6n.conso.appwrite;

import static org.assertj.core.api.Assertions.assertThat;

import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AppwriteRowsTest {

    @Test
    void releve() {
        var row =
                AppwriteRows.of(
                        new Reading(
                                "gdb",
                                "electricity",
                                "month",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 1, 31),
                                882,
                                "kWh",
                                311.0,
                                571.0,
                                null));

        assertThat(row)
                .containsEntry("provider", "gdb")
                .containsEntry("quantity", 882.0)
                .containsEntry("hp", 311.0)
                .containsEntry("periodEnd", "2026-01-31T12:00:00.000+00:00")
                .containsEntry("avgTemperature", null);
    }

    @Test
    void factureAvecReferenceDuPdf() {
        var row =
                AppwriteRows.of(
                        new Invoice(
                                "ileo",
                                "water",
                                "1000000003",
                                LocalDate.of(2026, 11, 13),
                                295.87,
                                64.0,
                                64.0,
                                "secret-ref",
                                null),
                        "ileo-1000000003");

        assertThat(row)
                .containsEntry("fileId", "ileo-1000000003")
                .containsEntry("amount", 295.87)
                .containsEntry("date", "2026-11-13T12:00:00.000+00:00")
                .doesNotContainKey("sourceRef"); // la référence fournisseur n'est jamais stockée
    }

    @Test
    void tarif() {
        var row =
                AppwriteRows.of(
                        new Tariff(
                                "gdb",
                                "gas",
                                LocalDate.of(2026, 11, 1),
                                "OFFRE GAZ",
                                0.1093,
                                null,
                                null,
                                30.06));

        assertThat(row)
                .containsEntry("kwh", 0.1093)
                .containsEntry("subscriptionMonthly", 30.06)
                .containsEntry("validFrom", "2026-11-01T12:00:00.000+00:00")
                .containsEntry("hp", null);
    }
}
