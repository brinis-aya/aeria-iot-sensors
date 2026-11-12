package fr.aeria.sensors.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import fr.aeria.sensors.config.AeriaProperties;
import fr.aeria.sensors.domain.Measurement;
import fr.aeria.sensors.domain.MeasurementRepository;
import fr.aeria.sensors.domain.Sensor;
import fr.aeria.sensors.domain.SensorRepository;
import fr.aeria.sensors.ingest.MeasurementPayload;
import fr.aeria.sensors.ingest.MeasurementValidator;

/**
 * Writes N days of simulated history straight into the database (bypassing MQTT), so the
 * pipeline and the forecasting model have something to learn from on day one.
 * Run with {@code --aeria.backfill.days=90}. Readings that ingestion would reject are
 * skipped, exactly as they would be live.
 */
@Component
@ConditionalOnProperty(prefix = "aeria.backfill", name = "days")
public class BackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

    private final AeriaProperties props;
    private final SensorRepository sensors;
    private final MeasurementRepository measurements;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int days;
    private final int stepMinutes;

    public BackfillRunner(AeriaProperties props, SensorRepository sensors, MeasurementRepository measurements,
                          TransactionTemplate tx, Clock clock,
                          @Value("${aeria.backfill.days}") int days,
                          @Value("${aeria.backfill.step-minutes:15}") int stepMinutes) {
        this.props = props;
        this.sensors = sensors;
        this.measurements = measurements;
        this.tx = tx;
        this.clock = clock;
        this.days = days;
        this.stepMinutes = stepMinutes;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant end = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant start = end.minus(Duration.ofDays(days));
        long total = 0;
        for (AeriaProperties.SimulatedSensor s : props.simulator().sensors()) {
            ReadingModel model = new ReadingModel(s.id(), s.environment(), props.simulator().faultRate(),
                    props.simulator().seed());
            Sensor sensor = sensors.findById(s.id()).orElseGet(() -> new Sensor(s.id(), start));
            sensor.describe(s.name(), s.latitude(), s.longitude(), s.environment());
            List<Measurement> batch = new ArrayList<>();
            for (Instant t = start; t.isBefore(end); t = t.plus(Duration.ofMinutes(stepMinutes))) {
                MeasurementPayload p = model.next(t, false, null, null, null);
                boolean ok = MeasurementValidator.validate(p, s.id(), t).valid();
                sensor.markSeen(t, ok);
                if (!ok || measurements.existsBySensorIdAndMeasuredAt(s.id(), t)) continue;
                batch.add(new Measurement(s.id(), t, t, p.pm25(), p.pm10(), p.no2(), p.o3(),
                        p.temperature(), p.humidity(), p.battery()));
            }
            tx.executeWithoutResult(status -> {
                sensors.save(sensor);
                measurements.saveAll(batch);
            });
            total += batch.size();
            log.info("Backfilled {} readings for {}", batch.size(), s.id());
        }
        log.info("Backfill done: {} readings over {} days", total, days);
    }
}
