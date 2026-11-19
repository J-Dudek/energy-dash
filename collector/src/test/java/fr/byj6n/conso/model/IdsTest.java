package fr.byj6n.conso.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void identifiantNormalInchange() {
        assertThat(Ids.of("gdb-electricity-2026-01-31")).isEqualTo("gdb-electricity-2026-01-31");
    }

    @Test
    void caracteresInterditsRetires() {
        assertThat(Ids.of("ileo-10/00 0;03\r\n")).isEqualTo("ileo-1000003");
    }

    @Test
    void jamaisDeDebutSpecialNiVide() {
        assertThat(Ids.of("--_.abc")).isEqualTo("abc");
        assertThat(Ids.of("///")).matches("x[0-9a-f]+");
    }

    @Test
    void identifiantTropLongRaccourciDeFacon_deterministe() {
        String raw = "ileo-" + "9".repeat(60);

        String id = Ids.of(raw);

        assertThat(id).hasSizeLessThanOrEqualTo(Ids.MAX).isEqualTo(Ids.of(raw));
        assertThat(Ids.of(raw + "1")).isNotEqualTo(id);
    }

    @Test
    void numeroDeFactureHostileNeFaitPasEchouerLaLigne() {
        var inv = new Invoice("ileo", "water", "../../etc/passwd\" ; x", LocalDate.of(2026, 1, 1), 1.0, 0.0, null, "r", null);

        assertThat(inv.rowId()).matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,35}");
    }
}
