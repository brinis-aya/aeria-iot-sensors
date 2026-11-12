package fr.aeria.sensors.ingest;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Edge validation done at ingestion time. It only rejects messages that are physically
 * impossible or unusable; statistical cleaning (spikes, drift, gaps) is done later by the
 * data pipeline (module 2), which needs the raw values to do it.
 */
public final class MeasurementValidator {

    private static final Pattern SENSOR_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** A reading cannot come from more than 10 minutes in the future (clock skew tolerance). */
    private static final Duration MAX_FUTURE = Duration.ofMinutes(10);
    /** Sensors buffer at most 7 days of readings when offline. */
    private static final Duration MAX_PAST = Duration.ofDays(7);

    public record Result(Instant measuredAt, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private MeasurementValidator() {
    }

    public static Result validate(MeasurementPayload p, String topicSensorId, Instant now) {
        List<String> errors = new ArrayList<>();
        Instant measuredAt = null;

        if (p.sensorId() == null || !SENSOR_ID.matcher(p.sensorId()).matches()) {
            errors.add("invalid sensorId");
        } else if (topicSensorId != null && !topicSensorId.equals(p.sensorId())) {
            errors.add("sensorId does not match topic");
        }

        if (p.ts() == null) {
            errors.add("missing ts");
        } else {
            try {
                measuredAt = Instant.parse(p.ts());
                if (measuredAt.isAfter(now.plus(MAX_FUTURE))) errors.add("ts in the future");
                if (measuredAt.isBefore(now.minus(MAX_PAST))) errors.add("ts older than 7 days");
            } catch (DateTimeParseException e) {
                errors.add("ts is not ISO-8601");
            }
        }

        if (p.pm25() == null && p.pm10() == null && p.no2() == null && p.o3() == null) {
            errors.add("no pollutant value");
        }
        range(errors, "pm25", p.pm25(), 0, 1000);
        range(errors, "pm10", p.pm10(), 0, 2000);
        range(errors, "no2", p.no2(), 0, 2000);
        range(errors, "o3", p.o3(), 0, 1000);
        range(errors, "temperature", p.temperature(), -40, 85);
        range(errors, "humidity", p.humidity(), 0, 100);
        range(errors, "battery", p.battery(), 0, 5);
        if (p.pm25() != null && p.pm10() != null && p.pm25() > p.pm10() * 1.05 + 1) {
            errors.add("pm25 greater than pm10");
        }
        range(errors, "lat", p.lat(), -90, 90);
        range(errors, "lon", p.lon(), -180, 180);

        return new Result(measuredAt, errors);
    }

    private static void range(List<String> errors, String field, Double v, double min, double max) {
        if (v == null) return;
        if (v.isNaN() || v < min || v > max) {
            errors.add(field + " out of range [" + min + ", " + max + "]");
        }
    }
}
