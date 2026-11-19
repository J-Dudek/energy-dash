package fr.byj6n.conso.alert;

import static org.assertj.core.api.Assertions.assertThat;

import fr.byj6n.conso.model.Reading;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsumptionAlertsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private static Reading month(String energy, int year, int month, double quantity) {
        LocalDate end = YearMonth.of(year, month).atEndOfMonth();
        return new Reading(
                "gdb",
                energy,
                "month",
                end.withDayOfMonth(1),
                end,
                quantity,
                "kWh",
                null,
                null,
                null);
    }

    private static List<Reading> plus(List<Reading> a, Reading... more) {
        List<Reading> all = new ArrayList<>(a);
        all.addAll(List.of(more));
        return all;
    }

    @Test
    void alerteQuandLeMoisDepasseLeSeuilParRapportAL_AnPasse() {
        Reading last = month("gas", 2026, 10, 450);
        List<Reading> all = List.of(month("gas", 2025, 10, 300), last);

        var alerts = ConsumptionAlerts.detect(all, List.of(last), 25, TODAY);

        assertThat(alerts).containsExactly(new Alert("gas", YearMonth.of(2026, 10), 450, 300, 50));
    }

    @Test
    void pasDAlerteSousLeSeuil() {
        Reading last = month("electricity", 2026, 10, 360);
        List<Reading> all = List.of(month("electricity", 2025, 10, 300), last); // +20 %

        assertThat(ConsumptionAlerts.detect(all, List.of(last), 25, TODAY)).isEmpty();
    }

    @Test
    void leSeuilEstInclus() {
        Reading last = month("gas", 2026, 10, 375);
        List<Reading> all = List.of(month("gas", 2025, 10, 300), last); // +25 % pile

        assertThat(ConsumptionAlerts.detect(all, List.of(last), 25, TODAY)).hasSize(1);
    }

    @Test
    void pasDAlerteSansLeMoisDeLAnneePrecedente() {
        Reading last = month("gas", 2026, 10, 900);

        assertThat(ConsumptionAlerts.detect(List.of(last), List.of(last), 25, TODAY)).isEmpty();
    }

    @Test
    void pasDAlertePourUneBaseNulle() {
        Reading last = month("gas", 2026, 7, 50);
        List<Reading> all = List.of(month("gas", 2025, 7, 0), last);

        assertThat(ConsumptionAlerts.detect(all, List.of(last), 25, TODAY)).isEmpty();
    }

    @Test
    void ignoreUnMoisEncoreEnCours() {
        // le relevé de novembre apparaît le 19 : il est partiel, on ne le compare pas
        Reading partial = month("gas", 2026, 11, 100);
        List<Reading> all = List.of(month("gas", 2025, 11, 60), partial);

        assertThat(ConsumptionAlerts.detect(all, List.of(partial), 25, TODAY)).isEmpty();
    }

    @Test
    void uneSeuleAlerteParEnergieLeMoisNouveauLePlusRecent() {
        Reading sept = month("gas", 2026, 9, 400);
        Reading oct = month("gas", 2026, 10, 600);
        List<Reading> all =
                List.of(month("gas", 2025, 9, 100), month("gas", 2025, 10, 200), sept, oct);

        var alerts = ConsumptionAlerts.detect(all, List.of(sept, oct), 25, TODAY);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).month()).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void ignoreLEauEtLesRelevesParFacture() {
        Reading water =
                new Reading(
                        "ileo",
                        "water",
                        "invoice",
                        TODAY.minusMonths(5),
                        TODAY.minusMonths(2),
                        90,
                        "m3",
                        null,
                        null,
                        null);
        List<Reading> all =
                List.of(
                        new Reading(
                                "ileo",
                                "water",
                                "invoice",
                                TODAY.minusMonths(17),
                                TODAY.minusMonths(14),
                                30,
                                "m3",
                                null,
                                null,
                                null),
                        water);

        assertThat(ConsumptionAlerts.detect(all, List.of(water), 25, TODAY)).isEmpty();
    }

    @Test
    void seuilNulDesactive() {
        Reading last = month("gas", 2026, 10, 900);
        List<Reading> all = List.of(month("gas", 2025, 10, 300), last);

        assertThat(ConsumptionAlerts.detect(all, List.of(last), 0, TODAY)).isEmpty();
    }

    @Test
    void electriciteEtGazSontIndependants() {
        Reading gas = month("gas", 2026, 10, 600);
        Reading elec = month("electricity", 2026, 10, 400);
        List<Reading> all =
                plus(
                        List.of(month("gas", 2025, 10, 300), month("electricity", 2025, 10, 390)),
                        gas,
                        elec);

        var alerts = ConsumptionAlerts.detect(all, List.of(gas, elec), 25, TODAY);

        assertThat(alerts).extracting(Alert::energy).containsExactly("gas");
    }
}
