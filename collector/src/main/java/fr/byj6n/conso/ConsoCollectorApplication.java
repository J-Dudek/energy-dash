package fr.byj6n.conso;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ConsoCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoCollectorApplication.class, args);
    }
}
