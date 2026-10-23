package org.kinotic.summit42.fakes;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.apache.hc.core5.http.HttpHost;
import org.kinotic.core.api.event.ZonePartitioningService;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.services.security.SignUpService;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.services.github.ProjectRepoCredentialService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.net.URISyntaxException;
import java.util.Set;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Stands in for the Kinotic modules Summit 42 runs in process, which the test profile leaves out: fakes of the Kinotic
 * services it calls, Elasticsearch in a container, a fake of the system server's LLM configurations published on
 * Summit 42's own node, a fake of Anthropic's API for those configurations to point at, and a fake of GitHub serving
 * Kinotic's agent plugin.
 */
@TestConfiguration
public class FakeKinoticConfiguration {

    // the version the Kinotic stack runs, started once for every test context of the JVM
    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.1"))
                    .withEnv("xpack.security.enabled", "false");

    /**
     * Hosts the system-api zone too, beside the management-api zone Summit 42 hosts, for the fake of the system-api
     * service.
     */
    @Bean
    @Primary
    public ZonePartitioningService fakeKinoticZonePartitioningService() {
        Set<String> zones = Set.of(DomainUtil.MANAGEMENT_API_ZONE, DomainUtil.SYSTEM_API_ZONE);
        return ZonePartitioningService.of("summit-42", zones, zones);
    }

    @Bean
    public ElasticsearchAsyncClient elasticsearchAsyncClient(JsonMapper jsonMapper) throws URISyntaxException {
        if (!ELASTICSEARCH.isRunning()) {
            ELASTICSEARCH.start();
        }
        Rest5Client client = Rest5Client.builder(HttpHost.create("http://" + ELASTICSEARCH.getHttpHostAddress())).build();
        return new ElasticsearchAsyncClient(new Rest5ClientTransport(client, new Jackson3JsonpMapper(jsonMapper)));
    }

    @Bean
    public FakeApplicationService fakeApplicationService(SecurityContext securityContext) {
        return fake(FakeApplicationService.class, securityContext);
    }

    @Bean
    public FakeProjectService fakeProjectService(SecurityContext securityContext) {
        return fake(FakeProjectService.class, securityContext);
    }

    @Bean
    public FakeUiDeploymentService fakeUiDeploymentService(SecurityContext securityContext) {
        return fake(FakeUiDeploymentService.class, securityContext);
    }

    @Bean
    public ProjectRepoCredentialService projectRepoCredentialService() {
        return mock(ProjectRepoCredentialService.class);
    }

    @Bean
    public FakeMemberInvitationService fakeMemberInvitationService(SecurityContext securityContext) {
        return fake(FakeMemberInvitationService.class, securityContext);
    }

    @Bean
    public SignUpService signUpService() {
        return mock(SignUpService.class);
    }

    @Bean
    public FakeLlmConfigurationService fakeLlmConfigurationService(SecurityContext securityContext) {
        return new FakeLlmConfigurationService(securityContext);
    }

    @Bean
    public FakeAnthropic fakeAnthropic(Vertx vertx) {
        return new FakeAnthropic(vertx);
    }

    @Bean
    public FakeGitHub fakeGitHub(Vertx vertx, WebClient webClient) {
        return new FakeGitHub(vertx, webClient);
    }

    // the fakes are abstract implementations of the Kinotic interfaces, and the methods they leave out answer defaults
    private static <T> T fake(Class<T> type, SecurityContext securityContext) {
        return mock(type, withSettings().useConstructor(securityContext).defaultAnswer(CALLS_REAL_METHODS));
    }
}
