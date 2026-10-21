package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.core.api.exceptions.AuthorizationException;
import org.kinotic.core.api.exceptions.RpcMissingServiceException;
import org.kinotic.core.api.security.Participant;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.model.security.participant.DefaultOrganizationParticipant;
import org.kinotic.domain.api.model.security.participant.DefaultSystemParticipant;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuildEventType;
import org.kinotic.summit42.api.model.ChatMessage;
import org.kinotic.summit42.api.model.ChatRole;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.internal.api.proxies.AppBuilderServiceProxy;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.kinotic.summit42.internal.api.repositories.BuilderSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Boots Summit 42 as a member of a cluster of its own and calls its {@code AppBuilderService} over Kinotic RPC,
 * sending each call as the participant the management gateway would deliver. The cluster runs none of the Kinotic
 * services Summit 42 calls, as when the management and system servers cannot be reached, so these tests cover what
 * the service does without them; a conversation a test needs is placed in {@link BuilderSessionRepository}, since a
 * chat turn that would record one needs the app's project and the platform's LLM.
 */
@SpringBootTest
@ActiveProfiles({"test"})
public class AppBuilderServiceTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") // Kinotic wires the proxy
    @Autowired
    private AppBuilderServiceProxy appBuilderService;
    @Autowired
    private SecurityContext securityContext;
    @Autowired
    private BuilderSessionRepository sessionRepository;

    @Test
    public void testEveryFunctionRefusesACallerThatIsNotAnOrganizationParticipant() {
        Participant system = DefaultSystemParticipant.builder()
                                                     .id("system-" + UUID.randomUUID())
                                                     .metadata(Map.of())
                                                     .roles(List.of())
                                                     .build();
        String applicationId = "app";

        assertRefused(callAs(system, AppBuilderServiceProxy::findApps));
        assertRefused(callAs(system, service -> service.createApp("Todo", null)));
        assertRefused(callAs(system, service -> service.findConversation(applicationId)));
        assertRefused(callAs(system, service -> service.resetConversation(applicationId)));
        StepVerifier.create(chatAs(system, applicationId, "Build a todo app"))
                    .expectError(AuthorizationException.class)
                    .verify(TIMEOUT);
    }

    @Test
    public void testChatWithAnEmptyMessageAnswersWithOneErrorEvent() {
        StepVerifier.create(chatAs(newOrganizationParticipant(), "app", "   "))
                    .assertNext(event -> {
                        Assertions.assertEquals(BuildEventType.ERROR, event.getType());
                        Assertions.assertEquals("The message is empty", event.getText());
                    })
                    .expectComplete()
                    .verify(TIMEOUT);
    }

    @Test
    public void testChatEndsWithAnErrorEventWhenTheAppsProjectCannotBeLookedUp() throws Exception {
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = "app";

        StepVerifier.create(chatAs(participant, applicationId, "Build a todo app"))
                    .assertNext(event -> {
                        Assertions.assertEquals(BuildEventType.ERROR, event.getType());
                        Assertions.assertEquals("Something went wrong. Please try again.", event.getText());
                    })
                    .expectComplete()
                    .verify(TIMEOUT);

        // the turn never started, so the message is not part of the conversation
        Conversation conversation = await(callAs(participant, service -> service.findConversation(applicationId)));
        Assertions.assertTrue(conversation.getMessages().isEmpty());
    }

    @Test
    public void testFindAppsFailsWhenTheOrganizationsAppsCannotBeLookedUp() {
        CompletableFuture<?> apps = callAs(newOrganizationParticipant(), AppBuilderServiceProxy::findApps);

        ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> await(apps));
        Assertions.assertInstanceOf(RpcMissingServiceException.class, e.getCause());
    }

    @Test
    public void testCreateAppRequiresAName() {
        CompletableFuture<?> created = callAs(newOrganizationParticipant(), service -> service.createApp("  ", "A todo app"));

        ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> await(created));
        Assertions.assertEquals("Name the app", e.getCause().getMessage());
    }

    @Test
    public void testFindConversationOfAnAppWithoutOneIsEmpty() throws Exception {
        Conversation conversation = await(callAs(newOrganizationParticipant(),
                                                 service -> service.findConversation("app")));

        Assertions.assertTrue(conversation.getMessages().isEmpty());
    }

    @Test
    public void testResetConversationOfAnAppWithoutOneSucceeds() {
        Assertions.assertDoesNotThrow(() -> await(callAs(newOrganizationParticipant(),
                                                         service -> service.resetConversation("app"))));
    }

    @Test
    public void testFindConversationReturnsTheMessagesInOrder() throws Exception {
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = "app";
        sessionOf(participant, applicationId);

        Conversation conversation = await(callAs(participant, service -> service.findConversation(applicationId)));

        Assertions.assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT),
                                conversation.getMessages().stream().map(ChatMessage::getRole).toList());
        Assertions.assertEquals(List.of("Build a todo app", "Your todo app is ready"),
                                conversation.getMessages().stream().map(ChatMessage::getText).toList());
    }

    @Test
    public void testConversationsAreIsolatedPerOrganization() throws Exception {
        OrganizationParticipant owner = newOrganizationParticipant();
        OrganizationParticipant other = newOrganizationParticipant();
        String applicationId = "app";
        sessionOf(owner, applicationId);

        Conversation seenByOther = await(callAs(other, service -> service.findConversation(applicationId)));
        await(callAs(other, service -> service.resetConversation(applicationId)));
        Conversation seenByOwner = await(callAs(owner, service -> service.findConversation(applicationId)));

        Assertions.assertTrue(seenByOther.getMessages().isEmpty());
        Assertions.assertEquals(2, seenByOwner.getMessages().size());
    }

    @Test
    public void testResetConversationClearsIt() throws Exception {
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = "app";
        sessionOf(participant, applicationId);

        await(callAs(participant, service -> service.resetConversation(applicationId)));
        Conversation conversation = await(callAs(participant, service -> service.findConversation(applicationId)));

        Assertions.assertTrue(conversation.getMessages().isEmpty());
    }

    @Test
    public void testResetConversationIsRefusedWhileATurnRuns() throws Exception {
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = "app";
        sessionOf(participant, applicationId).setTurnRunning(true);

        CompletableFuture<Void> reset = callAs(participant, service -> service.resetConversation(applicationId));
        ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> await(reset));
        Conversation conversation = await(callAs(participant, service -> service.findConversation(applicationId)));

        Assertions.assertTrue(e.getCause().getMessage().startsWith("Claude is still working on your last message"),
                              e.getCause().getMessage());
        Assertions.assertEquals(2, conversation.getMessages().size());
    }

    // a participant of an organization of its own, so no test sees another's conversations
    private static OrganizationParticipant newOrganizationParticipant() {
        String organizationId = "org-" + UUID.randomUUID();
        return DefaultOrganizationParticipant.builder()
                                             .id("user@" + organizationId)
                                             .organizationId(organizationId)
                                             .metadata(Map.of())
                                             .roles(List.of())
                                             .build();
    }

    // the app's session with one exchange in its conversation
    private BuilderSession sessionOf(OrganizationParticipant participant, String applicationId) {
        BuilderSession ret = sessionRepository.findOrCreate(new ApplicationKey(participant.getOrganizationId(),
                                                                               applicationId));
        ret.getConversation().getMessages().add(new ChatMessage(ChatRole.USER, "Build a todo app"));
        ret.getConversation().getMessages().add(new ChatMessage(ChatRole.ASSISTANT, "Your todo app is ready"));
        return ret;
    }

    // the proxy sends a call as the participant bound to the context it is invoked on
    private <T> CompletableFuture<T> callAs(Participant participant, Function<AppBuilderServiceProxy, Future<T>> call) {
        return securityContext.callAs(participant, () -> call.apply(appBuilderService).toCompletionStage());
    }

    // the stream's request carries the participant bound when chat is invoked, and is sent on subscription
    private Flux<BuildEvent> chatAs(Participant participant, String applicationId, String message) {
        return Mono.fromCompletionStage(securityContext.callAs(participant,
                                                               () -> CompletableFuture.completedFuture(
                                                                       appBuilderService.chat(applicationId, message))))
                   .flatMapMany(Function.identity());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static void assertRefused(CompletableFuture<?> future) {
        ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> await(future));
        Assertions.assertInstanceOf(AuthorizationException.class, e.getCause());
    }
}
