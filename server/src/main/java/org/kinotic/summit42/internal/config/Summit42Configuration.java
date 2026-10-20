package org.kinotic.summit42.internal.config;

import org.kinotic.core.api.event.ZonePartitioningService;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.summit42.api.services.AppBuilderService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * What Summit 42 is in the cluster: the zone it hosts and the zones it reaches.
 */
@Configuration
public class Summit42Configuration {

    /**
     * Hosts the summit-42 services, and reaches the management-api services that create and observe the
     * organization's apps and the system-api services that hold the LLM configurations and repository credentials.
     */
    @Bean
    public ZonePartitioningService zonePartitioningService() {
        return ZonePartitioningService.of("summit-42",
                                          Set.of(AppBuilderService.ZONE),
                                          Set.of(AppBuilderService.ZONE,
                                                 DomainUtil.MANAGEMENT_API_ZONE,
                                                 DomainUtil.SYSTEM_API_ZONE));
    }
}
