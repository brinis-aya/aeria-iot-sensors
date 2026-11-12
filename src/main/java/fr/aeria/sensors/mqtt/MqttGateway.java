package fr.aeria.sensors.mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import fr.aeria.sensors.config.AeriaProperties;
import fr.aeria.sensors.ingest.IngestionService;

/**
 * Single MQTT connection used both to subscribe (ingestion) and to publish (simulator).
 * Paho's automatic reconnect keeps it alive when the broker restarts; the subscription is
 * renewed in {@link #connectComplete}.
 */
@Component
@ConditionalOnProperty(prefix = "aeria.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttGateway implements SmartLifecycle, MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);

    private final AeriaProperties.Mqtt props;
    private final IngestionService ingestion;
    private MqttClient client;
    private volatile boolean running;

    public MqttGateway(AeriaProperties properties, IngestionService ingestion) {
        this.props = properties.mqtt();
        this.ingestion = ingestion;
    }

    @Override
    public void start() {
        try {
            client = new MqttClient(props.brokerUrl(), props.clientId(), new MemoryPersistence());
            client.setCallback(this);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(false);
            options.setKeepAliveInterval(30);
            if (props.username() != null && !props.username().isBlank()) {
                options.setUserName(props.username());
                options.setPassword(props.password() == null ? new char[0] : props.password().toCharArray());
            }
            client.connect(options);
            running = true;
            log.info("Connected to MQTT broker {}", props.brokerUrl());
        } catch (MqttException e) {
            throw new IllegalStateException("Cannot connect to MQTT broker " + props.brokerUrl(), e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (!props.ingestEnabled()) return;
        try {
            client.subscribe(props.subscribeTopic(), props.qos());
            log.info("{} to {}", reconnect ? "Re-subscribed" : "Subscribed", props.subscribeTopic());
        } catch (MqttException e) {
            log.error("Subscription failed", e);
        }
    }

    public void publish(String topic, String json) {
        if (client == null || !client.isConnected()) {
            log.debug("Not connected, dropping message for {}", topic);
            return;
        }
        try {
            MqttMessage msg = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            msg.setQos(props.qos());
            client.publish(topic, msg);
        } catch (MqttException e) {
            log.warn("Publish to {} failed: {}", topic, e.getMessage());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            ingestion.ingest(topic, message.getPayload());
        } catch (RuntimeException e) {
            // Never let an exception escape: Paho would drop the connection.
            log.error("Ingestion failed for {}", topic, e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {} (auto-reconnect on)", cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @Override
    public void stop() {
        running = false;
        if (client == null) return;
        try {
            if (client.isConnected()) client.disconnect();
            client.close();
        } catch (MqttException e) {
            log.debug("Error closing MQTT client", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
