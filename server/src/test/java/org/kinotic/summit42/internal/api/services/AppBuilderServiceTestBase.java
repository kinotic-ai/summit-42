package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.security.Participant;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.participant.DefaultOrganizationParticipant;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.internal.api.proxies.AppBuilderServiceProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Boots Summit 42 as a member of a cluster of its own and calls its {@code AppBuilderService} over Kinotic RPC,
 * sending each call as the participant the management gateway would deliver. Each test class gets a context of its
 * own, since the JVM runs one Ignite node at a time.
 */
@SpringBootTest
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AppBuilderServiceTestBase {

    protected static final Duration TIMEOUT = Duration.ofSeconds(30);

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") // Kinotic wires the proxy
    @Autowired
    protected AppBuilderServiceProxy appBuilderService;
    @Autowired
    protected SecurityContext securityContext;

    // a participant of an organization of its own, so no test sees another's apps or conversations
    protected static OrganizationParticipant newOrganizationParticipant() {
        String organizationId = "org-" + UUID.randomUUID();
        return DefaultOrganizationParticipant.builder()
                                             .id("user@" + organizationId)
                                             .organizationId(organizationId)
                                             .metadata(Map.of())
                                             .roles(List.of())
                                             .build();
    }

    // the proxy sends a call as the participant bound to the context it is invoked on
    protected <T> CompletableFuture<T> callAs(Participant participant, Function<AppBuilderServiceProxy, Future<T>> call) {
        return securityContext.callAs(participant, () -> call.apply(appBuilderService).toCompletionStage());
    }

    // the stream's request carries the participant bound when chat is invoked, and is sent on subscription
    protected Flux<BuildEvent> chatAs(Participant participant, String applicationId, String message) {
        return Mono.fromCompletionStage(securityContext.callAs(participant,
                                                               () -> CompletableFuture.completedFuture(
                                                                       appBuilderService.chat(applicationId, message))))
                   .flatMapMany(Function.identity());
    }

    protected static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
}
