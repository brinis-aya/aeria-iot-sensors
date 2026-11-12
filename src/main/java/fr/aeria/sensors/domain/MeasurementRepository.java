package fr.aeria.sensors.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementRepository extends JpaRepository<Measurement, Long> {

    boolean existsBySensorIdAndMeasuredAt(String sensorId, Instant measuredAt);

    List<Measurement> findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(String sensorId, Instant from, Instant to);

    Optional<Measurement> findFirstBySensorIdOrderByMeasuredAtDesc(String sensorId);

    List<Measurement> findByMeasuredAtAfterOrderByMeasuredAtAsc(Instant after, Pageable pageable);
}
