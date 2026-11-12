package fr.aeria.sensors.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.aeria.sensors.domain.Measurement;
import fr.aeria.sensors.domain.MeasurementRepository;
import fr.aeria.sensors.domain.Sensor;
import fr.aeria.sensors.domain.SensorRepository;

@Service
public class IngestionService {

    public enum Outcome { STORED, DUPLICATE, REJECTED, MALFORMED }

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final ObjectMapper mapper;
    private final SensorRepository sensors;
    private final MeasurementRepository measurements;
    private final Clock clock;

    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();

    public IngestionService(ObjectMapper mapper, SensorRepository sensors,
                            MeasurementRepository measurements, Clock clock) {
        this.mapper = mapper;
        this.sensors = sensors;
        this.measurements = measurements;
        this.clock = clock;
    }

    /** Extracts {sensorId} from {@code aeria/sensors/{sensorId}/measurements}. */
    static String sensorIdFromTopic(String topic) {
        if (topic == null) return null;
        String[] parts = topic.split("/");
        return parts.length >= 3 ? parts[parts.length - 2] : null;
    }

    @Transactional
    public Outcome ingest(String topic, byte[] body) {
        MeasurementPayload p;
        try {
            p = mapper.readValue(body, MeasurementPayload.class);
        } catch (Exception e) {
            malformed.incrementAndGet();
            log.warn("Malformed message on {}: {}", topic, e.getMessage());
            return Outcome.MALFORMED;
        }

        Instant now = clock.instant();
        MeasurementValidator.Result check = MeasurementValidator.validate(p, sensorIdFromTopic(topic), now);

        if (p.sensorId() != null && check.errors().stream().noneMatch(e -> e.contains("sensorId"))) {
            Sensor sensor = sensors.findById(p.sensorId()).orElseGet(() -> new Sensor(p.sensorId(), now));
            sensor.describe(p.name(), p.lat(), p.lon(), p.env());
            sensor.markSeen(now, check.valid());
            sensors.save(sensor);
        }

        if (!check.valid()) {
            rejected.incrementAndGet();
            log.info("Rejected reading from {}: {}", p.sensorId(), check.errors());
            return Outcome.REJECTED;
        }

        if (measurements.existsBySensorIdAndMeasuredAt(p.sensorId(), check.measuredAt())) {
            // QoS 1 means "at least once": the broker may redeliver, so ingestion is idempotent.
            duplicates.incrementAndGet();
            return Outcome.DUPLICATE;
        }

        measurements.save(new Measurement(p.sensorId(), check.measuredAt(), now,
                p.pm25(), p.pm10(), p.no2(), p.o3(), p.temperature(), p.humidity(), p.battery()));
        stored.incrementAndGet();
        return Outcome.STORED;
    }

    public Outcome ingest(String topic, MeasurementPayload payload) {
        try {
            return ingest(topic, mapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Stats stats() {
        return new Stats(stored.get(), duplicates.get(), rejected.get(), malformed.get());
    }

    public record Stats(long stored, long duplicates, long rejected, long malformed) {
    }
}
