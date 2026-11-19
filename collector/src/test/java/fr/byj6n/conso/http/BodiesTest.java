package fr.byj6n.conso.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BodiesTest {

    @Test
    void litUnCorpsSousLePlafond() throws Exception {
        assertThat(Bodies.readAtMost(new ByteArrayInputStream(new byte[100]), 100)).hasSize(100);
    }

    @Test
    void refuseUnCorpsAuDessusDuPlafond() {
        assertThatThrownBy(() -> Bodies.readAtMost(new ByteArrayInputStream(new byte[101]), 100))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("trop volumineuse");
    }

    @Test
    void reconnaitLaSignatureDUnPdf() {
        assertThat(Bodies.isPdf("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(Bodies.isPdf("<html>".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(Bodies.isPdf(new byte[3])).isFalse();
    }
}
