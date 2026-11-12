package fr.aeria.sensors.simulator;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.aeria.sensors.config.AeriaProperties;
import fr.aeria.sensors.ingest.MeasurementPayload;
import fr.aeria.sensors.mqtt.MqttGateway;

/**
 * Publishes one reading per simulated sensor every {@code aeria.simulator.period-ms}
 * over real MQTT, exactly like a physical ESP32 would.
 */
@Component
@ConditionalOnProperty(prefix = "aeria.simulator", name = "enabled", havingValue = "true")
public class SensorSimulator {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulator.class);

    private final AeriaProperties props;
    private final MqttGateway mqtt;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, ReadingModel> models = new LinkedHashMap<>();
    private long tick;

    public SensorSimulator(AeriaProperties props, MqttGateway mqtt, ObjectMapper mapper, Clock clock) {
        this.props = props;
        this.mqtt = mqtt;
        this.mapper = mapper;
        this.clock = clock;
        AeriaProperties.Simulator sim = props.simulator();
        for (AeriaProperties.SimulatedSensor s : sim.sensors()) {
            models.put(s.id(), new ReadingModel(s.id(), s.environment(), sim.faultRate(), sim.seed()));
        }
        log.info("Simulator started with {} sensors, one reading every {} ms", models.size(), sim.periodMs());
    }

    @Scheduled(fixedRateString = "${aeria.simulator.period-ms}", initialDelay = 2000)
    public void publishAll() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        // Re-send the sensor description every 60 ticks, like a device does after a reboot.
        boolean describe = tick++ % 60 == 0;
        for (AeriaProperties.SimulatedSensor s : props.simulator().sensors()) {
            MeasurementPayload reading = models.get(s.id())
                    .next(now, describe, s.name(), s.latitude(), s.longitude());
            try {
                mqtt.publish(props.mqtt().topicPrefix() + "/" + s.id() + "/measurements",
                        mapper.writeValueAsString(reading));
            } catch (JsonProcessingException e) {
                log.error("Cannot serialise reading", e);
            }
        }
    }
}
