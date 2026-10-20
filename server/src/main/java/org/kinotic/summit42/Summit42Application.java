package org.kinotic.summit42;

import org.kinotic.core.api.annotations.EnableKinotic;
import org.kinotic.domain.internal.config.KinoticDomainJacksonConfig;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Summit 42, the chat app builder: a Kinotic cluster member that hosts the {@code summit-42} zone's
 * {@link org.kinotic.summit42.api.services.AppBuilderService} and serves the Summit 42 UI.
 */
// The Kinotic modules are on the classpath for their model types and proxied services, which the cluster's
// servers host; their own services need Elasticsearch, so their auto-configuration is excluded. The
// participant subtypes that event senders deserialize to are registered by KinoticDomainJacksonConfig.
// The auto-configurations are named rather than referenced, since kinotic-sql and kinotic-grind are only runtime
// dependencies.
@SpringBootApplication(excludeName = {"org.kinotic.domain_autoconfig.KinoticDomainAutoConfiguration",
                                      "org.kinotic.sql_autoconfig.KinoticSqlAutoConfiguration",
                                      "org.kinotic.grind_autoconfig.KinoticGrindAutoConfiguration",
                                      "org.kinotic.management_autoconfig.KinoticManagementApiAutoConfiguration",
                                      "org.kinotic.system_autoconfig.KinoticSystemApiAutoConfiguration"})
@EnableKinotic
@EnableConfigurationProperties(Summit42Properties.class)
@Import(KinoticDomainJacksonConfig.class)
public class Summit42Application {

    static void main(String[] args) {
        SpringApplication.run(Summit42Application.class, args);
    }
}
