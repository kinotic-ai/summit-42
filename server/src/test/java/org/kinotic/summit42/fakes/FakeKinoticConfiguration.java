package org.kinotic.summit42.fakes;

import io.vertx.core.Vertx;
import org.kinotic.core.api.event.ZonePartitioningService;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Set;

/**
 * Runs fakes of the Kinotic services Summit 42 calls on Summit 42's own node, a fake of Anthropic's API for the
 * LLM configurations to point at, and a fake of GitHub serving Kinotic's Claude plugin.
 */
@TestConfiguration
public class FakeKinoticConfiguration {

    /**
     * Hosts the system-api zone too, beside the management-api zone Summit 42 hosts, for the fakes of system-api
     * services.
     */
    @Bean
    @Primary
    public ZonePartitioningService fakeKinoticZonePartitioningService() {
        Set<String> zones = Set.of(DomainUtil.MANAGEMENT_API_ZONE, DomainUtil.SYSTEM_API_ZONE);
        return ZonePartitioningService.of("summit-42", zones, zones);
    }

    @Bean
    public FakeApplicationService fakeApplicationService(SecurityContext securityContext) {
        return new FakeApplicationService(securityContext);
    }

    @Bean
    public FakeProjectService fakeProjectService(SecurityContext securityContext) {
        return new FakeProjectService(securityContext);
    }

    @Bean
    public FakeUiDeploymentService fakeUiDeploymentService(SecurityContext securityContext) {
        return new FakeUiDeploymentService(securityContext);
    }

    @Bean
    public FakeLlmConfigurationService fakeLlmConfigurationService(SecurityContext securityContext) {
        return new FakeLlmConfigurationService(securityContext);
    }

    @Bean
    public FakeAnthropic fakeAnthropic(Vertx vertx) {
        return new FakeAnthropic(vertx);
    }

    /**
     * Serves Kinotic's Claude plugin, and points Summit 42 at it before the first chat turn reads its skills.
     */
    @Bean
    public FakeGitHub fakeGitHub(Vertx vertx, Summit42Properties properties) {
        FakeGitHub ret = new FakeGitHub(vertx);
        properties.setKinoticPluginRepositoryUrl(ret.repositoryUrl());
        return ret;
    }
}
