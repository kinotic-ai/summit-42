package org.kinotic.summit42.internal.config;

import io.vertx.core.Vertx;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.kinotic.core.api.event.ZonePartitioningService;
import org.kinotic.domain.api.utils.DomainUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * What Summit 42 is in the cluster, the zone it hosts and the zones it reaches, and the beans it shares with the
 * Kinotic modules it runs.
 */
@Configuration
public class Summit42Configuration {

    /**
     * Hosts Summit 42's services in the management-api zone, beside the management-api services it runs in process,
     * and reaches the system-api service that holds the LLM configurations.
     */
    @Bean
    public ZonePartitioningService zonePartitioningService() {
        return ZonePartitioningService.of("summit-42",
                                          Set.of(DomainUtil.MANAGEMENT_API_ZONE),
                                          Set.of(DomainUtil.MANAGEMENT_API_ZONE, DomainUtil.SYSTEM_API_ZONE));
    }

    /**
     * The HTTP client Summit 42 calls GitHub's API and reads Kinotic's documentation with, identifying itself as
     * summit-42.
     */
    @Bean
    public WebClient webClient(Vertx vertx) {
        return WebClient.create(vertx, new WebClientOptions().setUserAgent("summit-42"));
    }

    /**
     * The checks {@code /health} answers with, which the Kinotic modules register theirs with, such as Elasticsearch's.
     */
    @Bean
    public HealthChecks healthChecks(Vertx vertx) {
        return HealthChecks.create(vertx);
    }
}
