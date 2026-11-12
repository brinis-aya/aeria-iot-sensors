package fr.aeria.sensors.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import fr.aeria.sensors.ingest.MeasurementPayload;
import fr.aeria.sensors.ingest.MeasurementValidator;

class ReadingModelTest {

    @Test
    void rushHourIsMorePollutedThanNight() {
        assertThat(ReadingModel.trafficFactor(8)).isGreaterThan(ReadingModel.trafficFactor(3) * 2);
        assertThat(ReadingModel.trafficFactor(18)).isGreaterThan(ReadingModel.trafficFactor(3) * 2);
    }

    @Test
    void withoutFaultsEveryReadingPassesValidation() {
        ReadingModel model = new ReadingModel("LH-002", "traffic", 0.0, 42);
        Instant t = Instant.parse("2026-11-10T00:00:00Z");
        for (int i = 0; i < 2000; i++) {
            Instant at = t.plus(i * 5L, ChronoUnit.MINUTES);
            MeasurementPayload p = model.next(at, i == 0, "Boulevard", 49.49, 0.11);
            var check = MeasurementValidator.validate(p, "LH-002", at);
            assertThat(check.errors()).as("reading %d: %s", i, p).isEmpty();
        }
    }

    @Test
    void sameSeedGivesSameSeries() {
        Instant at = Instant.parse("2026-11-10T08:00:00Z");
        var a = new ReadingModel("LH-001", "urban", 0.05, 7).next(at, false, null, null, null);
        var b = new ReadingModel("LH-001", "urban", 0.05, 7).next(at, false, null, null, null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void faultsAreInjectedAtRoughlyTheConfiguredRate() {
        ReadingModel model = new ReadingModel("LH-004", "industrial", 0.10, 1);
        Instant t = Instant.parse("2026-11-10T00:00:00Z");
        int rejected = 0;
        for (int i = 0; i < 3000; i++) {
            Instant at = t.plus(i, ChronoUnit.MINUTES);
            if (!MeasurementValidator.validate(model.next(at, false, null, null, null), null, at).valid()) rejected++;
        }
        // one fault type in three is an impossible value that ingestion rejects: ~3.3 %
        assertThat(rejected).isBetween(40, 180);
    }
}
