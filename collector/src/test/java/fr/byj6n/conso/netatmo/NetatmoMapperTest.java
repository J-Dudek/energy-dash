package fr.byj6n.conso.netatmo;

import fr.byj6n.conso.model.Reading;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Les JSON reprennent la forme documentée par Netatmo (valeurs fictives). */
class NetatmoMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void trouveLeThermostatEtSonRelais() throws Exception {
        var json = mapper.readTree("""
                {"body":{"homes":[{"modules":[
                  {"id":"70:ee:50:00:00:01","type":"NAPlug"},
                  {"id":"04:00:00:00:00:02","type":"NATherm1","bridge":"70:ee:50:00:00:01"}]}]}}
                """);

        var t = NetatmoMapper.thermostat(json).orElseThrow();

        assertThat(t.deviceId()).isEqualTo("70:ee:50:00:00:01");
        assertThat(t.moduleId()).isEqualTo("04:00:00:00:00:02");
    }

    @Test
    void pasDeThermostat() throws Exception {
        var json = mapper.readTree("""
                {"body":{"homes":[{"modules":[{"id":"x","type":"NRV","bridge":"y"}]}]}}
                """);

        assertThat(NetatmoMapper.thermostat(json)).isEmpty();
    }

    @Test
    void heuresDeChauffeFormatParHorodatage() throws Exception {
        // 1763420400 = 2025-11-17 23:00 UTC = 18/11/2025 00:00 à Paris
        var json = mapper.readTree("""
                {"status":"ok","body":{"1763420400":[14820],"1763506800":[9300],"1763593200":[null]}}
                """);

        List<Reading> r = NetatmoMapper.heating(json);

        assertThat(r).hasSize(2);
        Reading first = r.getFirst();
        assertThat(first.provider()).isEqualTo("netatmo");
        assertThat(first.energy()).isEqualTo("heating");
        assertThat(first.granularity()).isEqualTo("day");
        assertThat(first.periodEnd()).isEqualTo(LocalDate.of(2025, 11, 18));
        assertThat(first.quantity()).isEqualTo(4.12); // 14820 s
        assertThat(first.unit()).isEqualTo("h");
        assertThat(first.rowId()).isEqualTo("netatmo-heating-2025-11-18");
        assertThat(r.get(1).periodEnd()).isEqualTo(LocalDate.of(2025, 11, 19));
    }

    @Test
    void heuresDeChauffeFormatParPas() throws Exception {
        var json = mapper.readTree("""
                {"status":"ok","body":[{"beg_time":1763420400,"step_time":86400,"value":[[7200],[3600],[null]]}]}
                """);

        List<Reading> r = NetatmoMapper.heating(json);

        assertThat(r).extracting(Reading::periodEnd)
                .containsExactly(LocalDate.of(2025, 11, 18), LocalDate.of(2025, 11, 19));
        assertThat(r).extracting(Reading::quantity).containsExactly(2.0, 1.0);
    }

    @Test
    void reponseVide() throws Exception {
        assertThat(NetatmoMapper.heating(mapper.readTree("{\"status\":\"ok\",\"body\":{}}"))).isEmpty();
        assertThat(NetatmoMapper.heating(mapper.readTree("{}"))).isEmpty();
    }
}
