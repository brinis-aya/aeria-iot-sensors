package fr.aeria.sensors.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.aeria.sensors.domain.MeasurementRepository;
import fr.aeria.sensors.domain.SensorRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestionServiceTest {

    @Autowired IngestionService ingestion;
    @Autowired MeasurementRepository measurements;
    @Autowired SensorRepository sensors;
    @Autowired MockMvc mvc;

    private final String ts = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30).toString();

    @BeforeEach
    void clean() {
        measurements.deleteAll();
        sensors.deleteAll();
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void storesAValidMessageAndRegistersTheSensor() {
        var out = ingestion.ingest("aeria/sensors/LH-001/measurements", json("""
                {"sensorId":"LH-001","ts":"%s","pm25":12.1,"pm10":19.0,"no2":30,
                 "name":"Hôtel de Ville","lat":49.49,"lon":0.10,"env":"urban"}""".formatted(ts)));

        assertThat(out).isEqualTo(IngestionService.Outcome.STORED);
        assertThat(measurements.count()).isEqualTo(1);
        var sensor = sensors.findById("LH-001").orElseThrow();
        assertThat(sensor.getName()).isEqualTo("Hôtel de Ville");
        assertThat(sensor.getMessageCount()).isEqualTo(1);
    }

    @Test
    void redeliveryIsIdempotent() {
        byte[] msg = json("""
                {"sensorId":"LH-002","ts":"%s","pm25":5,"pm10":9}""".formatted(ts));
        assertThat(ingestion.ingest("aeria/sensors/LH-002/measurements", msg)).isEqualTo(IngestionService.Outcome.STORED);
        assertThat(ingestion.ingest("aeria/sensors/LH-002/measurements", msg)).isEqualTo(IngestionService.Outcome.DUPLICATE);
        assertThat(measurements.count()).isEqualTo(1);
    }

    @Test
    void invalidReadingIsCountedButNotStored() {
        var out = ingestion.ingest("aeria/sensors/LH-003/measurements", json("""
                {"sensorId":"LH-003","ts":"%s","pm25":5,"pm10":9,"no2":-1}""".formatted(ts)));
        assertThat(out).isEqualTo(IngestionService.Outcome.REJECTED);
        assertThat(measurements.count()).isZero();
        assertThat(sensors.findById("LH-003").orElseThrow().getRejectedCount()).isEqualTo(1);
    }

    @Test
    void garbageIsMalformed() {
        assertThat(ingestion.ingest("aeria/sensors/x/measurements", json("not json")))
                .isEqualTo(IngestionService.Outcome.MALFORMED);
    }

    @Test
    void restApiExposesSensorsAndHistory() throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sensorId":"LH-005","ts":"%s","pm25":7,"pm10":11,"no2":12}""".formatted(ts)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/sensors")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("LH-005"))
                .andExpect(jsonPath("$[0].online").value(true));
        mvc.perform(get("/api/sensors/LH-005/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pm25").value(7.0));
        mvc.perform(get("/api/measurements/export").param("after", "2000-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/sensors/NOPE/latest")).andExpect(status().isNotFound());
    }
}
