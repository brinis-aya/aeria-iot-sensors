package fr.aeria.sensors.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fr.aeria.sensors.domain.Measurement;
import fr.aeria.sensors.domain.MeasurementRepository;
import fr.aeria.sensors.domain.Sensor;
import fr.aeria.sensors.domain.SensorRepository;
import fr.aeria.sensors.ingest.IngestionService;
import fr.aeria.sensors.ingest.MeasurementPayload;

@RestController
@RequestMapping("/api")
public class SensorController {

    private final SensorRepository sensors;
    private final MeasurementRepository measurements;
    private final IngestionService ingestion;

    public SensorController(SensorRepository sensors, MeasurementRepository measurements, IngestionService ingestion) {
        this.sensors = sensors;
        this.measurements = measurements;
        this.ingestion = ingestion;
    }

    @GetMapping("/sensors")
    public List<Map<String, Object>> listSensors() {
        Instant now = Instant.now();
        return sensors.findAll().stream().map(s -> Map.<String, Object>of(
                "id", s.getId(),
                "name", s.getName() == null ? "" : s.getName(),
                "latitude", s.getLatitude() == null ? 0 : s.getLatitude(),
                "longitude", s.getLongitude() == null ? 0 : s.getLongitude(),
                "environment", s.getEnvironment() == null ? "" : s.getEnvironment(),
                "lastSeen", s.getLastSeen(),
                "online", Duration.between(s.getLastSeen(), now).toMinutes() < 5,
                "messageCount", s.getMessageCount(),
                "rejectedCount", s.getRejectedCount())).toList();
    }

    @GetMapping("/sensors/{id}")
    public Sensor sensor(@PathVariable String id) {
        return sensors.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/sensors/{id}/latest")
    public Measurement latest(@PathVariable String id) {
        return measurements.findFirstBySensorIdOrderByMeasuredAtDesc(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/sensors/{id}/measurements")
    public List<Measurement> history(@PathVariable String id,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        return measurements.findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(id, start, end);
    }

    /** Incremental export used by the data pipeline (module 2): everything after a watermark. */
    @GetMapping("/measurements/export")
    public List<Measurement> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant after,
                                    @RequestParam(defaultValue = "5000") int limit) {
        return measurements.findByMeasuredAtAfterOrderByMeasuredAtAsc(after, PageRequest.of(0, Math.min(limit, 20000)));
    }

    /** HTTP fallback for devices that cannot speak MQTT (same validation path). */
    @PostMapping("/measurements")
    public ResponseEntity<Map<String, String>> post(@RequestBody MeasurementPayload payload) {
        IngestionService.Outcome outcome = ingestion.ingest(null, payload);
        HttpStatus status = switch (outcome) {
            case STORED -> HttpStatus.CREATED;
            case DUPLICATE -> HttpStatus.OK;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("outcome", outcome.name()));
    }

    @GetMapping("/stats")
    public IngestionService.Stats stats() {
        return ingestion.stats();
    }
}
