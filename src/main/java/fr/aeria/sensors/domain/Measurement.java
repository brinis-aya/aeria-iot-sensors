package fr.aeria.sensors.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One reading of one sensor. Concentrations are in µg/m³, temperature in °C, humidity in %.
 * Null means "this sensor does not measure it".
 */
@Entity
@Table(name = "measurement", uniqueConstraints = @UniqueConstraint(columnNames = {"sensor_id", "measured_at"}))
public class Measurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_id", nullable = false, length = 64)
    private String sensorId;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    private Double pm25;
    private Double pm10;
    private Double no2;
    private Double o3;
    private Double temperature;
    private Double humidity;
    private Double battery;

    protected Measurement() {
    }

    public Measurement(String sensorId, Instant measuredAt, Instant receivedAt,
                       Double pm25, Double pm10, Double no2, Double o3,
                       Double temperature, Double humidity, Double battery) {
        this.sensorId = sensorId;
        this.measuredAt = measuredAt;
        this.receivedAt = receivedAt;
        this.pm25 = pm25;
        this.pm10 = pm10;
        this.no2 = no2;
        this.o3 = o3;
        this.temperature = temperature;
        this.humidity = humidity;
        this.battery = battery;
    }

    public Long getId() { return id; }
    public String getSensorId() { return sensorId; }
    public Instant getMeasuredAt() { return measuredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public Double getPm25() { return pm25; }
    public Double getPm10() { return pm10; }
    public Double getNo2() { return no2; }
    public Double getO3() { return o3; }
    public Double getTemperature() { return temperature; }
    public Double getHumidity() { return humidity; }
    public Double getBattery() { return battery; }
}
