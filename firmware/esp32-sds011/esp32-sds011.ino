// Aeria - real sensor node: ESP32 + Nova SDS011 particle sensor + BME280 (temperature/humidity)
// Publishes the same JSON as the simulator on aeria/sensors/<SENSOR_ID>/measurements.
//
// Wiring (ESP32 DevKit v1):
//   SDS011 TX -> GPIO16 (RX2), SDS011 RX -> GPIO17 (TX2), 5V -> VIN, GND -> GND
//   BME280 SDA -> GPIO21, SCL -> GPIO22, VCC -> 3V3, GND -> GND
//
// Libraries (Arduino Library Manager): PubSubClient, ArduinoJson (v7), Adafruit BME280.
// Copy secrets.example.h to secrets.h and fill in your Wi-Fi and broker settings.

#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Adafruit_BME280.h>
#include <time.h>
#include "secrets.h"

static const uint32_t MEASURE_EVERY_MS = 60UL * 1000UL;   // one reading per minute
static const uint32_t SDS_WARMUP_MS    = 30UL * 1000UL;   // fan spin-up before reading

HardwareSerial sds(2);
WiFiClient net;
PubSubClient mqtt(net);
Adafruit_BME280 bme;
bool bmeOk = false;
bool described = false;
String topic;

// Reads one 10-byte SDS011 frame: AA C0 PM25L PM25H PM10L PM10H ID1 ID2 CHK AB
bool readSds(float &pm25, float &pm10) {
  uint32_t start = millis();
  while (millis() - start < 3000) {
    if (sds.available() >= 10 && sds.read() == 0xAA) {
      uint8_t b[9];
      sds.readBytes(b, 9);
      if (b[0] != 0xC0 || b[8] != 0xAB) continue;
      uint8_t sum = 0;
      for (int i = 1; i <= 6; i++) sum += b[i];
      if (sum != b[7]) continue;                       // checksum error
      pm25 = (b[1] | (b[2] << 8)) / 10.0f;
      pm10 = (b[3] | (b[4] << 8)) / 10.0f;
      return true;
    }
    delay(10);
  }
  return false;
}

void connectWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) delay(500);
  configTime(0, 0, "pool.ntp.org", "time.google.com");     // UTC timestamps
  time_t now = 0;
  while (now < 1700000000) { delay(200); time(&now); }
}

void connectMqtt() {
  while (!mqtt.connected()) {
    // Username = sensor id: the broker ACL only lets it write its own topic.
    if (!mqtt.connect(SENSOR_ID, SENSOR_ID, MQTT_PASSWORD)) delay(5000);
  }
}

void setup() {
  Serial.begin(115200);
  sds.begin(9600, SERIAL_8N1, 16, 17);
  bmeOk = bme.begin(0x76);
  topic = String("aeria/sensors/") + SENSOR_ID + "/measurements";
  connectWifi();
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setBufferSize(512);
}

void loop() {
  static uint32_t last = 0;
  if (WiFi.status() != WL_CONNECTED) connectWifi();
  connectMqtt();
  mqtt.loop();
  if (last != 0 && millis() - last < MEASURE_EVERY_MS) return;
  last = millis();

  delay(SDS_WARMUP_MS);
  float pm25, pm10;
  if (!readSds(pm25, pm10)) { Serial.println("SDS011 read failed"); return; }

  time_t now; time(&now);
  char ts[25];
  strftime(ts, sizeof ts, "%Y-%m-%dT%H:%M:%SZ", gmtime(&now));

  JsonDocument doc;
  doc["sensorId"] = SENSOR_ID;
  doc["ts"] = ts;
  doc["pm25"] = pm25;
  doc["pm10"] = pm10;
  if (bmeOk) {
    doc["temperature"] = round(bme.readTemperature() * 10) / 10.0;
    doc["humidity"] = round(bme.readHumidity() * 10) / 10.0;
  }
  doc["battery"] = analogReadMilliVolts(35) * 2 / 1000.0;  // voltage divider on GPIO35
  if (!described) {
    doc["name"] = SENSOR_NAME; doc["lat"] = SENSOR_LAT; doc["lon"] = SENSOR_LON; doc["env"] = SENSOR_ENV;
  }

  char out[512];
  size_t n = serializeJson(doc, out);
  if (mqtt.publish(topic.c_str(), (const uint8_t *)out, n, false)) {
    described = true;
    Serial.println(out);
  }
}
