package fr.aeria.sensors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AeriaSensorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AeriaSensorsApplication.class, args);
    }
}
