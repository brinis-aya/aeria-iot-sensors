package fr.aeria.sensors.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSON message published by a sensor (simulated or ESP32) on
 * {@code aeria/sensors/{sensorId}/measurements}.
 *
 * <pre>
 * {"sensorId":"LH-001","ts":"2026-11-12T10:00:00Z","pm25":12.4,"pm10":20.1,
 *  "no2":31.0,"o3":null,"temperature":11.2,"humidity":81,"battery":3.91,
 *  "name":"Le Havre - Hôtel de ville","lat":49.4938,"lon":0.1077,"env":"urban"}
 * </pre>
 * The descriptive fields (name, lat, lon, env) are optional; a sensor usually sends them
 * in its first message after boot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeasurementPayload(
        String sensorId,
        String ts,
        Double pm25,
        Double pm10,
        Double no2,
        Double o3,
        Double temperature,
        Double humidity,
        Double battery,
        String name,
        Double lat,
        Double lon,
        String env) {
}
