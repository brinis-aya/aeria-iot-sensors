package fr.aeria.sensors.simulator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Random;

import fr.aeria.sensors.ingest.MeasurementPayload;

/**
 * Generates plausible readings for one sensor: a daily cycle with rush-hour peaks
 * (traffic NO2 and particles), an ozone peak in the afternoon, a slow random walk for
 * the weather, and, with probability {@code faultRate}, a faulty reading (spike, frozen
 * value, negative value) so that the cleaning step of the pipeline has real work to do.
 */
public final class ReadingModel {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final String sensorId;
    private final String environment;
    private final double faultRate;
    private final Random random;

    private double weather;      // slow-moving pollution episode factor, around 1.0
    private double temperature;
    private double battery = 4.1;
    private MeasurementPayload last;

    public ReadingModel(String sensorId, String environment, double faultRate, long seed) {
        this.sensorId = sensorId;
        this.environment = environment == null ? "urban" : environment;
        this.faultRate = faultRate;
        this.random = new Random(seed ^ sensorId.hashCode());
        this.weather = 1.0;
        this.temperature = 10 + random.nextGaussian() * 2;
    }

    /** Traffic profile: two rush-hour bumps (8h and 18h local time). */
    static double trafficFactor(double hour) {
        double morning = Math.exp(-Math.pow(hour - 8.0, 2) / 3.0);
        double evening = Math.exp(-Math.pow(hour - 18.0, 2) / 4.0);
        return 0.45 + 0.9 * morning + 0.75 * evening;
    }

    private double envFactor() {
        return switch (environment) {
            case "traffic" -> 1.6;
            case "industrial" -> 1.35;
            case "rural" -> 0.55;
            default -> 1.0;
        };
    }

    public MeasurementPayload next(Instant at, boolean withDescription,
                                   String name, Double lat, Double lon) {
        ZonedDateTime local = at.atZone(PARIS);
        double hour = local.getHour() + local.getMinute() / 60.0;
        boolean weekend = local.getDayOfWeek().getValue() >= 6;

        weather = clamp(weather + random.nextGaussian() * 0.03 + (1.0 - weather) * 0.01, 0.4, 3.0);
        temperature = clamp(temperature + random.nextGaussian() * 0.15
                + 0.4 * Math.sin((hour - 9) / 24 * 2 * Math.PI) * 0.05, -10, 38);
        battery = Math.max(3.3, battery - 0.00002);
        double humidity = clamp(85 - (temperature - 8) * 2.2 + random.nextGaussian() * 3, 20, 100);

        double traffic = trafficFactor(hour) * (weekend ? 0.7 : 1.0);
        double f = envFactor() * weather;

        double no2 = 18 * f * traffic + noise(3);
        double pm10 = 14 * f * (0.6 + 0.4 * traffic) + noise(2.5);
        double pm25 = pm10 * clamp(0.55 + humidity / 400 + noise(0.04), 0.4, 0.9);
        double sun = Math.max(0, Math.sin((hour - 6) / 14 * Math.PI));
        double o3 = 35 + 45 * sun * (temperature / 20) - 0.4 * no2 + noise(4);

        Double vPm25 = round(Math.max(0, pm25));
        Double vPm10 = round(Math.max(0, pm10));
        Double vNo2 = round(Math.max(0, no2));
        Double vO3 = "rural".equals(environment) || "urban".equals(environment) ? round(Math.max(0, o3)) : null;

        if (random.nextDouble() < faultRate) {
            switch (random.nextInt(3)) {
                case 0 -> { vPm25 = round(vPm25 * (6 + random.nextDouble() * 6)); vPm10 = round(Math.max(vPm10, vPm25 * 1.1)); } // spike
                case 1 -> { if (last != null) { vPm25 = last.pm25(); vPm10 = last.pm10(); vNo2 = last.no2(); } } // frozen
                default -> vNo2 = -1.0; // electronic glitch: rejected at ingestion
            }
        }

        MeasurementPayload p = new MeasurementPayload(sensorId, at.toString(),
                vPm25, vPm10, vNo2, vO3, round(temperature), round(humidity), round(battery),
                withDescription ? name : null, withDescription ? lat : null,
                withDescription ? lon : null, withDescription ? environment : null);
        last = p;
        return p;
    }

    private double noise(double sigma) {
        return random.nextGaussian() * sigma;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
