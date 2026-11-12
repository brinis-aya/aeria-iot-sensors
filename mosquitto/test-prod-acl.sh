#!/bin/sh
# Verifies the production authentication and ACL inside the running broker container:
#   docker cp mosquitto/mosquitto.prod.conf aeria-mosquitto-1:/tmp/ && docker cp mosquitto/acl aeria-mosquitto-1:/tmp/
#   docker cp mosquitto/test-prod-acl.sh aeria-mosquitto-1:/tmp/ && docker exec aeria-mosquitto-1 sh /tmp/test-prod-acl.sh
# A second broker is started on 127.0.0.1:1884 with the same auth/ACL settings (TLS listener omitted).
set -u
cd /tmp
rm -f passwd && touch passwd && chmod 600 passwd
mosquitto_passwd -b passwd LH-001 test-lh001 && mosquitto_passwd -b passwd aeria-ingest test-ingest
# the broker drops to the mosquitto user: secrets readable by it only (as mosquitto 2 recommends)
chown mosquitto:mosquitto passwd acl && chmod 0700 passwd acl
sed -e 's#/mosquitto/config/passwd#/tmp/passwd#; s#/mosquitto/config/acl#/tmp/acl#' \
    -e '/^listener 8883/,/^tls_version/d' -e 's#^listener 1883 127.0.0.1#listener 1884 127.0.0.1#' \
    -e 's#^persistence true#persistence false#' mosquitto.prod.conf > prod-test.conf
mosquitto -c prod-test.conf -d && sleep 1
ok=0; ko=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1 (expected $3, got $2)"; ko=$((ko+1)); fi; }

mosquitto_pub -h 127.0.0.1 -p 1884 -t aeria/sensors/LH-001/measurements -m x >/dev/null 2>&1; check "anonymous client refused" "$?" "5"
mosquitto_pub -h 127.0.0.1 -p 1884 -u LH-001 -P wrong -t aeria/sensors/LH-001/measurements -m x >/dev/null 2>&1; check "wrong password refused" "$?" "5"

mosquitto_sub -h 127.0.0.1 -p 1884 -u aeria-ingest -P test-ingest -t 'aeria/sensors/#' -v -W 4 > received.txt 2>/dev/null &
sleep 1
mosquitto_pub -h 127.0.0.1 -p 1884 -u LH-001 -P test-lh001 -q 1 -t aeria/sensors/LH-001/measurements -m own-topic
mosquitto_pub -h 127.0.0.1 -p 1884 -u LH-001 -P test-lh001 -q 1 -t aeria/sensors/LH-002/measurements -m spoofed
wait
check "sensor can publish its own topic" "$(grep -c own-topic received.txt)" "1"
check "sensor cannot publish as another sensor" "$(grep -c spoofed received.txt)" "0"
pkill -f prod-test.conf; rm -f passwd prod-test.conf received.txt
echo "$ok passed, $ko failed"; [ "$ko" -eq 0 ]
