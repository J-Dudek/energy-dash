package fr.byj6n.conso.gdb;

import tools.jackson.databind.ObjectMapper;
import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Les JSON reprennent la forme relevée sur espaceclient.gazdebordeaux.fr (valeurs fictives). */
class GazDeBordeauxMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void electriciteHeuresPleinesCreuses() throws Exception {
        var json = mapper.readTree("""
                {"consumptions":[
                  {"consumption":{"base":0,"hp":127,"hc":259},"label":"11-2025","averageTemperature":4},
                  {"consumption":{"base":0,"hp":359,"hc":527},"label":"12-2025","averageTemperature":6}
                ],"lastMonth":{"consumed":{"base":0,"hp":10,"hc":20},"month":"11-2026","billed":0}}
                """);
        List<Reading> readings = GazDeBordeauxMapper.readings("elec", json);

        assertThat(readings).hasSize(2);
        Reading nov = readings.getFirst();
        assertThat(nov.energy()).isEqualTo("electricity");
        assertThat(nov.quantity()).isEqualTo(386.0);
        assertThat(nov.hp()).isEqualTo(127.0);
        assertThat(nov.hc()).isEqualTo(259.0);
        assertThat(nov.periodStart()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(nov.periodEnd()).isEqualTo(LocalDate.of(2025, 11, 30));
        assertThat(nov.avgTemperature()).isEqualTo(4.0);
        assertThat(nov.rowId()).isEqualTo("gdb-electricity-2025-11-30");
    }

    @Test
    void electriciteOptionBase() throws Exception {
        var json = mapper.readTree("""
                {"consumptions":[{"consumption":{"base":300,"hp":0,"hc":0},"label":"01-2026"}]}
                """);
        Reading r = GazDeBordeauxMapper.readings("elec", json).getFirst();
        assertThat(r.quantity()).isEqualTo(300.0);
        assertThat(r.hp()).isNull();
        assertThat(r.avgTemperature()).isNull();
    }

    @Test
    void gaz() throws Exception {
        var json = mapper.readTree("""
                {"consumptions":[{"consumption":812.5,"label":"02-2026","averageTemperature":5}]}
                """);
        String segment = GazDeBordeauxMapper.apiSegment("gaz");
        assertThat(segment).isEqualTo("gas");
        Reading r = GazDeBordeauxMapper.readings(segment, json).getFirst();
        assertThat(r.energy()).isEqualTo("gas");
        assertThat(r.quantity()).isEqualTo(812.5);
        assertThat(r.periodEnd()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void factures() throws Exception {
        var json = mapper.readTree("""
                {"invoices":[
                  {"amount":1396.05,"isPaid":true,"id":"abc","date":"05/07/2026","type":"elec","code":"88000001"},
                  {"amount":512.4,"isPaid":false,"id":"def","date":"05/07/2026","type":"gaz","code":"88000002"}
                ]}
                """);
        List<Invoice> invoices = GazDeBordeauxMapper.invoices(1234567L, json);

        assertThat(invoices).hasSize(2);
        Invoice elec = invoices.getFirst();
        assertThat(elec.energy()).isEqualTo("electricity");
        assertThat(elec.date()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(elec.amountDue()).isEqualTo(0.0);
        assertThat(elec.sourceRef()).isEqualTo("elec|abc");
        assertThat(elec.rowId()).isEqualTo("gdb-88000001");
        Invoice gaz = invoices.get(1);
        assertThat(gaz.energy()).isEqualTo("gas");
        assertThat(gaz.amountDue()).isEqualTo(512.4);
    }

    @Test
    void tarifElectriciteHpHc() throws Exception {
        var json = mapper.readTree("""
                {"tarifScale":{"dateDebut":"2025-06-01","libelleOffre":"OFFRE ELEC",
                  "hp":{"prixConsoUniteWithTaxeTTC":0.1779},"hc":{"prixConsoUniteWithTaxeTTC":0.141636},
                  "abo":{"prixAboTTC":15.87},"base":null}}
                """);
        Tariff t = GazDeBordeauxMapper.tariff("elec", json);

        assertThat(t.energy()).isEqualTo("electricity");
        assertThat(t.validFrom()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(t.hp()).isEqualTo(0.1779);
        assertThat(t.hc()).isEqualTo(0.141636);
        assertThat(t.kwh()).isNull();
        assertThat(t.subscriptionMonthly()).isEqualTo(15.87);
        assertThat(t.rowId()).isEqualTo("gdb-electricity-2025-06-01");
    }

    @Test
    void tarifGaz() throws Exception {
        var json = mapper.readTree("""
                {"tarifScale":{"dateDebut":"2025-06-01","dateDebutValiditePrix":"2026-11-01",
                  "prixTotalTTCConso":0.1093,"prixTotalTTCAbo":30.06,"libelleOffre":"OFFRE GAZ"}}
                """);
        Tariff t = GazDeBordeauxMapper.tariff("gaz", json);

        assertThat(t.energy()).isEqualTo("gas");
        assertThat(t.validFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(t.kwh()).isEqualTo(0.1093);
        assertThat(t.subscriptionMonthly()).isEqualTo(30.06);
    }

    @Test
    void tarifAbsent() throws Exception {
        assertThat(GazDeBordeauxMapper.tariff("gaz", mapper.readTree("{}"))).isNull();
    }

    @Test
    void ligneIllisibleIgnoreeSansPerdreLesAutres() throws Exception {
        var releves = mapper.readTree("""
                {"consumptions":[
                  {"consumption":{"base":0,"hp":1,"hc":2},"label":"pas-un-mois"},
                  {"consumption":{"base":0,"hp":127,"hc":259},"label":"11-2025"}]}
                """);
        var factures = mapper.readTree("""
                {"invoices":[
                  {"amount":1.0,"isPaid":true,"id":"a","date":"31/02/2026","type":"elec","code":"BAD"},
                  {"amount":2.0,"isPaid":true,"id":"b","date":"05/07/2026","type":"elec","code":"OK"}]}
                """);

        assertThat(GazDeBordeauxMapper.readings("elec", releves)).hasSize(1);
        assertThat(GazDeBordeauxMapper.invoices(1L, factures)).extracting(Invoice::number).containsExactly("OK");
    }
}
