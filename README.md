# Aeria · IoT sensors

[![CI](https://github.com/brinis-aya/aeria-iot-sensors/actions/workflows/ci.yml/badge.svg)](https://github.com/brinis-aya/aeria-iot-sensors/actions/workflows/ci.yml)

Module 1 of **Aeria**, an open air-quality monitoring platform. This repository holds the sensor layer:
simulated (or real ESP32) air-quality sensors publish readings over **MQTT**, and a **Spring Boot**
service validates them and stores them in **PostgreSQL**.

> Other modules: [data pipeline](https://github.com/brinis-aya/aeria-data-pipeline) ·
> [24 h forecast](https://github.com/brinis-aya/aeria-forecast) ·
> [back-office](https://github.com/brinis-aya/aeria-backoffice) ·
> [security](https://github.com/brinis-aya/aeria-security) ·
> [dashboard](https://github.com/brinis-aya/aeria-dashboard) ·
> [overview](https://github.com/brinis-aya/aeria)

```
 ESP32 + SDS011  ─┐
                  ├─ MQTT (QoS 1) ─▶ Mosquitto ─▶ Spring Boot ingest ─▶ PostgreSQL ─▶ pipeline (module 2)
 Simulator ×6    ─┘   aeria/sensors/{id}/measurements      │  validation, dedup
                                                           └─ REST API  /api/sensors, /api/measurements/export
```

## What it does

- **Simulator**: six virtual sensors around Le Havre (city centre, traffic boulevard, port, industrial
  zone, residential, rural). Readings follow a daily cycle with rush-hour peaks, an afternoon ozone peak,
  slow-moving pollution episodes, and a configurable rate of faulty readings (spikes, frozen values,
  impossible values) so the data-cleaning step downstream has real work to do.
- **Real hardware**: an Arduino sketch for an ESP32 with a Nova SDS011 particle sensor and a BME280
  (temperature/humidity) that publishes the exact same JSON — see [`firmware/`](firmware/esp32-sds011).
- **Ingestion**: subscribes to `aeria/sensors/+/measurements`, rejects physically impossible readings,
  checks that the sensor id in the payload matches the topic, and is idempotent (MQTT QoS 1 can redeliver).
- **Backfill**: generates N days of history directly in the database so later modules have data to learn from.
- **REST API** for the dashboard and the pipeline.

## Run it

Requirements: Docker, or Java 21 + Maven.

```bash
docker compose up -d --build        # broker, database, ingestion service, simulator
curl localhost:8081/api/sensors     # six sensors, online
curl localhost:8081/api/stats       # stored / duplicates / rejected / malformed counters
```

Without Docker for the Java part:

```bash
docker compose up -d mosquitto postgres
mvn package -DskipTests
java -jar target/aeria-iot-sensors.jar                                   # ingestion
SIMULATOR_ENABLED=true INGEST_ENABLED=false PORT=8082 MQTT_CLIENT_ID=aeria-sim \
  java -jar target/aeria-iot-sensors.jar                                 # simulator
java -jar target/aeria-iot-sensors.jar --aeria.backfill.days=120 \
  --aeria.mqtt.enabled=false --spring.main.web-application-type=none     # 120 days of history
```

PostgreSQL is published on `127.0.0.1:5440` (override with `PG_PORT`).

## Message format

Topic `aeria/sensors/{sensorId}/measurements`, QoS 1, UTF-8 JSON, 4 KiB max:

```json
{"sensorId":"LH-002","ts":"2026-11-12T12:15:47Z","pm25":11.5,"pm10":14.7,"no2":4.9,
 "temperature":6.2,"humidity":95.4,"battery":4.1,
 "name":"Boulevard de Strasbourg","lat":49.492,"lon":0.118,"env":"traffic"}
```

Concentrations in µg/m³. `name`, `lat`, `lon`, `env` are optional and usually sent once after boot.

| Rule | Why |
|---|---|
| `sensorId` matches `[A-Za-z0-9_-]{1,64}` and the topic | stops a sensor writing as another one, and injection in ids |
| `ts` ISO-8601, not > 10 min in the future, not > 7 days old | clock skew tolerance; devices buffer at most 7 days offline |
| at least one pollutant; each value in a physical range | rejects electronic glitches (negative values, saturation) |
| `pm25 ≤ pm10` (+5 % tolerance) | PM2.5 is a subset of PM10 |
| unique `(sensor_id, measured_at)` | QoS 1 redelivery must not create duplicates |

Statistical cleaning (spikes, frozen sensors, gaps) is deliberately **not** done here: the pipeline
needs the raw values to detect them.

## API

| Method | Path | |
|---|---|---|
| GET | `/api/sensors` | all sensors with `online` (seen < 5 min) and counters |
| GET | `/api/sensors/{id}/latest` | last reading |
| GET | `/api/sensors/{id}/measurements?from=&to=` | history (default last 24 h) |
| GET | `/api/measurements/export?after=&limit=` | incremental export for the pipeline |
| POST | `/api/measurements` | HTTP fallback for devices without MQTT |
| GET | `/api/stats`, `/actuator/health` | monitoring |

## Tests

```bash
mvn verify      # 16 tests: validation rules, simulator realism, ingestion + REST against H2
```

## Hardening for production

The compose file is for development: the broker accepts anonymous clients but is only published on
`127.0.0.1`. For a real deployment: TLS listener on 8883, `allow_anonymous false`, one password per
sensor (`mosquitto_passwd`), and the ACL in [`mosquitto/acl`](mosquitto/acl) that lets each sensor write
only its own topic. These points are tracked in the risk analysis of the
[security module](https://github.com/brinis-aya/aeria-security).

## Report

The full write-up (in French) is in [`docs/rapport.md`](docs/rapport.md).

## Licence

MIT
