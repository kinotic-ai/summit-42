package org.kinotic.summit42.internal.api.services;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.core.api.exceptions.AuthorizationException;
import org.kinotic.core.api.security.Participant;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.model.security.participant.DefaultSystemParticipant;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.api.model.BuildEventType;
import org.kinotic.summit42.api.model.ChatMessage;
import org.kinotic.summit42.api.model.ChatRole;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.internal.api.proxies.AppBuilderServiceProxy;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.kinotic.summit42.internal.api.repositories.BuilderSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Covers who may call {@code AppBuilderService} and what it does with an app's conversation. A conversation a test
 * needs is placed in {@link BuilderSessionRepository}, since a chat turn that would record one needs the app's project
 * and the platform's LLM.
 */
public class AppBuilderServiceTests extends AppBuilderServiceTestBase {

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

    // the app's session with one exchange in its conversation
    private BuilderSession sessionOf(OrganizationParticipant participant, String applicationId) {
        BuilderSession ret = sessionRepository.findById(new ApplicationKey(participant.getOrganizationId(), applicationId))
                                              .toCompletionStage().toCompletableFuture().join();
        ret.getConversation().getMessages().add(new ChatMessage(ChatRole.USER, "Build a todo app"));
        ret.getConversation().getMessages().add(new ChatMessage(ChatRole.ASSISTANT, "Your todo app is ready"));
        return ret;
    }

    private static void assertRefused(CompletableFuture<?> future) {
        ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> await(future));
        Assertions.assertInstanceOf(AuthorizationException.class, e.getCause());
    }
}
