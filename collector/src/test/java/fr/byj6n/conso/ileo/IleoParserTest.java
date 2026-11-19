package fr.byj6n.conso.ileo;

import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IleoParserTest {

    private static String fixture(String name) throws IOException {
        try (var in = IleoParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fusionneVolumesEtMontants() throws IOException {
        var rows = IleoParser.parse(fixture("ileo-consommations-facturees.html"), fixture("ileo-gerer-factures.html"));

        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().date()).isEqualTo(LocalDate.of(2025, 11, 12)); // trié croissant
        var last = rows.getLast();
        assertThat(last.number()).isEqualTo("1000000003");
        assertThat(last.volume()).isEqualTo(64.0);
        assertThat(last.amount()).isEqualTo(295.87);
        assertThat(last.amountDue()).isEqualTo(64.0);
        assertThat(last.cleDoc()).isEqualTo("DOC3ABC");
        assertThat(rows.getFirst().volume()).isEqualTo(1067.0);
        assertThat(rows.getFirst().amount()).isEqualTo(1012.10);
    }

    @Test
    void relevesChainesEntreFactures() throws IOException {
        var rows = IleoParser.parse(fixture("ileo-consommations-facturees.html"), fixture("ileo-gerer-factures.html"));
        List<Reading> readings = IleoParser.readings(rows);

        assertThat(readings).hasSize(3);
        Reading second = readings.get(1);
        assertThat(second.periodStart()).isEqualTo(LocalDate.of(2025, 11, 13));
        assertThat(second.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 2));
        assertThat(second.quantity()).isEqualTo(84.0);
        assertThat(second.unit()).isEqualTo("m3");
        assertThat(second.rowId()).isEqualTo("ileo-water-2026-06-02");
    }

    @Test
    void facturesAvecReferencePdf() throws IOException {
        var rows = IleoParser.parse(fixture("ileo-consommations-facturees.html"), fixture("ileo-gerer-factures.html"));
        List<Invoice> invoices = IleoParser.invoices(rows);

        assertThat(invoices).extracting(Invoice::rowId)
                .containsExactly("ileo-1000000001", "ileo-1000000002", "ileo-1000000003");
        assertThat(invoices).allMatch(i -> i.sourceRef() != null && i.energy().equals("water"));
    }

    @Test
    void detectePageDeConnexion() {
        String login = "<form method=post><input name=email><input type=password name=password>"
                + "<input type=hidden name=connexion value=1></form>";
        assertThat(IleoParser.isLoginPage(login)).isTrue();
        assertThat(IleoParser.isLoginPage("<table><tr><td>13/11/2026</td></tr></table>")).isFalse();
    }

    @Test
    void parseNombresFrancais() {
        assertThat(IleoParser.parseNumber("1 234,56 €")).isEqualTo(1234.56);
        assertThat(IleoParser.parseNumber("64 m3")).isEqualTo(64.0);
        assertThat(IleoParser.parseNumber("—")).isNull();
    }

    @Test
    void ligneAvecDateInvalideIgnoree() {
        String conso = """
                <table><tr><td>31/02/2026</td><td>BAD</td><td>10 m3</td></tr>
                <tr><td>13/11/2026</td><td>OK</td><td>64 m3</td></tr></table>""";

        var rows = IleoParser.parse(conso, null);

        assertThat(rows).extracting(IleoParser.Row::number).containsExactly("OK");
    }
}
