package org.kinotic.summit42.internal.api.services;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.core.api.exceptions.AuthorizationException;
import org.kinotic.core.api.security.Participant;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.management.api.model.RepositoryConnectionStatus;
import org.kinotic.domain.api.model.security.participant.DefaultSystemParticipant;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.api.model.BuildEventType;
import org.kinotic.summit42.api.model.ChatMessage;
import org.kinotic.summit42.api.model.ChatRole;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.fakes.FakeApplicationService;
import org.kinotic.summit42.fakes.FakeProjectService;
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
    @Autowired
    private FakeApplicationService applicationService;
    @Autowired
    private FakeProjectService projectService;

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
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = appOf(participant);

        Conversation conversation = await(callAs(participant, service -> service.findConversation(applicationId)));

        Assertions.assertTrue(conversation.getMessages().isEmpty());
    }

    @Test
    public void testResetConversationOfAnAppWithoutOneSucceeds() {
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = appOf(participant);

        Assertions.assertDoesNotThrow(() -> await(callAs(participant, service -> service.resetConversation(applicationId))));
    }

    @Test
    public void testTheConversationOfAnAppWithNoProjectIsRefused() {
        OrganizationParticipant participant = newOrganizationParticipant();

        for (CompletableFuture<?> call : List.of(callAs(participant, service -> service.findConversation("unknown")),
                                                 callAs(participant, service -> service.resetConversation("unknown")))) {
            ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> await(call));
            Assertions.assertEquals("This app no longer exists", e.getCause().getMessage());
        }
    }

    @Test
    public void testFindConversationReturnsTheMessagesInOrder() throws Exception {
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = appOf(participant);
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
        // an app of the same name in each organization, so both have its id
        String applicationId = appOf(owner);
        appOf(other);
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
        String applicationId = appOf(participant);
        sessionOf(participant, applicationId);

        await(callAs(participant, service -> service.resetConversation(applicationId)));
        Conversation conversation = await(callAs(participant, service -> service.findConversation(applicationId)));

        Assertions.assertTrue(conversation.getMessages().isEmpty());
    }

    @Test
    public void testResetConversationIsRefusedWhileATurnRuns() throws Exception {
        OrganizationParticipant participant = newOrganizationParticipant();
        String applicationId = appOf(participant);
        sessionOf(participant, applicationId).setTurnRunning(true);

        CompletableFuture<Void> reset = callAs(participant, service -> service.resetConversation(applicationId));
        ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> await(reset));
        Conversation conversation = await(callAs(participant, service -> service.findConversation(applicationId)));

        Assertions.assertTrue(e.getCause().getMessage().startsWith("Claude is still working on your last message"),
                              e.getCause().getMessage());
        Assertions.assertEquals(2, conversation.getMessages().size());
    }

    // an app of the participant's organization with a project, as createApp leaves one
    private String appOf(OrganizationParticipant participant) {
        String ret = applicationService.add(participant.getOrganizationId(), "Todo", null).getId();
        projectService.add(participant.getOrganizationId(), ret, RepositoryConnectionStatus.CONNECTED);
        return ret;
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
