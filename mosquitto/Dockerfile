FROM eclipse-mosquitto:2.0.20
COPY mosquitto.conf acl /mosquitto/config/
RUN chown mosquitto:mosquitto /mosquitto/config/acl && chmod 0700 /mosquitto/config/acl
