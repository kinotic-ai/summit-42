package org.kinotic.summit42;

import org.kinotic.core.api.annotations.EnableKinotic;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.kinotic.system_autoconfig.KinoticSystemApiAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Summit 42, the chat app builder: a Kinotic cluster member that hosts
 * {@link org.kinotic.summit42.api.services.AppBuilderService} in the {@code management-api} zone and serves the
 * Summit 42 UI.
 */
// Summit 42 runs Kinotic's domain and management-api modules in process and calls their services directly. The
// system server runs system-api, whose services Summit 42 calls over the cluster, so its auto-configuration is excluded.
@SpringBootApplication(exclude = KinoticSystemApiAutoConfiguration.class)
@EnableKinotic
@EnableConfigurationProperties(Summit42Properties.class)
public class Summit42Application {

    static void main(String[] args) {
        SpringApplication.run(Summit42Application.class, args);
    }
}
