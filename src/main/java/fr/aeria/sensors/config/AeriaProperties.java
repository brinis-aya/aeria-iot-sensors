package fr.aeria.sensors.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables of the service, bound from the {@code aeria.*} keys of application.yml.
 */
@ConfigurationProperties(prefix = "aeria")
public record AeriaProperties(Mqtt mqtt, Simulator simulator) {

    public record Mqtt(
            boolean enabled,
            String brokerUrl,
            String clientId,
            String username,
            String password,
            /** Topic filter the ingestion service subscribes to. */
            String subscribeTopic,
            /** Prefix used by the simulator: {prefix}/{sensorId}/measurements. */
            String topicPrefix,
            int qos,
            boolean ingestEnabled) {
    }

    public record Simulator(
            boolean enabled,
            long periodMs,
            /** Probability (0..1) that a simulated reading is an outlier, to exercise data cleaning. */
            double faultRate,
            long seed,
            List<SimulatedSensor> sensors) {
    }

    public record SimulatedSensor(String id, String name, double latitude, double longitude, String environment) {
    }
}
