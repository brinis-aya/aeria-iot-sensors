package fr.aeria.sensors.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class MeasurementValidatorTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private static MeasurementPayload reading(String id, String ts, Double pm25, Double pm10, Double no2) {
        return new MeasurementPayload(id, ts, pm25, pm10, no2, null, 11.0, 80.0, 3.9, null, null, null, null);
    }

    @Test
    void acceptsAPlausibleReading() {
        var r = MeasurementValidator.validate(reading("LH-001", "2026-11-12T09:59:50Z", 12.0, 20.0, 30.0), "LH-001", NOW);
        assertThat(r.valid()).isTrue();
        assertThat(r.measuredAt()).isEqualTo(Instant.parse("2026-11-12T09:59:50Z"));
    }

    @Test
    void rejectsNegativeConcentration() {
        var r = MeasurementValidator.validate(reading("LH-001", "2026-11-12T09:59:50Z", 12.0, 20.0, -1.0), "LH-001", NOW);
        assertThat(r.errors()).anyMatch(e -> e.startsWith("no2"));
    }

    @Test
    void rejectsPm25AbovePm10() {
        var r = MeasurementValidator.validate(reading("LH-001", "2026-11-12T09:59:50Z", 50.0, 20.0, 10.0), "LH-001", NOW);
        assertThat(r.errors()).contains("pm25 greater than pm10");
    }

    @Test
    void rejectsSpoofedTopic() {
        var r = MeasurementValidator.validate(reading("LH-001", "2026-11-12T09:59:50Z", 5.0, 9.0, 10.0), "LH-999", NOW);
        assertThat(r.errors()).contains("sensorId does not match topic");
    }

    @Test
    void rejectsBadTimestamps() {
        assertThat(MeasurementValidator.validate(reading("LH-001", "yesterday", 5.0, 9.0, 1.0), null, NOW).errors())
                .contains("ts is not ISO-8601");
        assertThat(MeasurementValidator.validate(reading("LH-001", "2026-11-12T11:00:00Z", 5.0, 9.0, 1.0), null, NOW).errors())
                .contains("ts in the future");
        assertThat(MeasurementValidator.validate(reading("LH-001", "2026-10-01T00:00:00Z", 5.0, 9.0, 1.0), null, NOW).errors())
                .contains("ts older than 7 days");
    }

    @Test
    void rejectsInjectionInSensorId() {
        var r = MeasurementValidator.validate(reading("x'; DROP TABLE sensor;--", "2026-11-12T09:59:50Z", 5.0, 9.0, 1.0), null, NOW);
        assertThat(r.errors()).contains("invalid sensorId");
    }

    @Test
    void requiresAtLeastOnePollutant() {
        var r = MeasurementValidator.validate(reading("LH-001", "2026-11-12T09:59:50Z", null, null, null), null, NOW);
        assertThat(r.errors()).contains("no pollutant value");
    }
}
