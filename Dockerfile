# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# --- runtime stage: small JRE image, non-root user ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S aeria && adduser -S aeria -G aeria
WORKDIR /app
COPY --from=build /src/target/aeria-iot-sensors.jar app.jar
USER aeria
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:${PORT:-8081}/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
