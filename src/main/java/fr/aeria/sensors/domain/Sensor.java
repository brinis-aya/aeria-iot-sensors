package fr.aeria.sensors.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sensor")
public class Sensor {

    @Id
    @Column(length = 64)
    private String id;

    private String name;

    private Double latitude;

    private Double longitude;

    /** urban, traffic, industrial, rural... */
    private String environment;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Column(name = "message_count", nullable = false)
    private long messageCount;

    @Column(name = "rejected_count", nullable = false)
    private long rejectedCount;

    protected Sensor() {
    }

    public Sensor(String id, Instant seenAt) {
        this.id = id;
        this.firstSeen = seenAt;
        this.lastSeen = seenAt;
    }

    public void markSeen(Instant at, boolean accepted) {
        if (at.isAfter(lastSeen)) {
            lastSeen = at;
        }
        if (accepted) {
            messageCount++;
        } else {
            rejectedCount++;
        }
    }

    public void describe(String name, Double latitude, Double longitude, String environment) {
        if (name != null) this.name = name;
        if (latitude != null) this.latitude = latitude;
        if (longitude != null) this.longitude = longitude;
        if (environment != null) this.environment = environment;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getEnvironment() { return environment; }
    public Instant getFirstSeen() { return firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public long getMessageCount() { return messageCount; }
    public long getRejectedCount() { return rejectedCount; }
}
