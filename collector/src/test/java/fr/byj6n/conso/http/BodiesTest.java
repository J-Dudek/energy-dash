package fr.byj6n.conso.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
