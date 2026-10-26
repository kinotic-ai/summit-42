package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kinotic.core.api.ServiceRegistry;
import org.kinotic.core.api.service.ServiceIdentifier;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.model.TenantManagement;
import org.kinotic.domain.api.model.security.OidcProviderType;
import org.kinotic.domain.api.model.security.OwnedOidcConfiguration;
import org.kinotic.domain.api.model.security.PasswordConfiguration;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.ProjectRepoToken;
import org.kinotic.management.api.model.ProjectType;
import org.kinotic.management.api.model.RepositoryConnectionStatus;
import org.kinotic.management.api.model.deployment.DeploymentState;
import org.kinotic.management.api.model.deployment.DeploymentStatusType;
import org.kinotic.management.api.model.deployment.MicroserviceDeployment;
import org.kinotic.management.api.model.deployment.ProjectDeployment;
import org.kinotic.management.api.model.deployment.UiDeployment;
import org.kinotic.management.api.services.github.ProjectRepoCredentialService;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.kinotic.summit42.api.model.AppCreationEvent;
import org.kinotic.summit42.api.model.AppCreationEventType;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuildEventType;
import org.kinotic.summit42.api.model.BuildStepType;
import org.kinotic.summit42.api.model.BuilderApp;
import org.kinotic.summit42.api.model.ChatMessage;
import org.kinotic.summit42.api.model.ChatRole;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.fakes.FakeAnthropic;
import org.kinotic.summit42.fakes.FakeApplicationService;
import org.kinotic.summit42.fakes.FakeGitHub;
import org.kinotic.summit42.fakes.FakeLlmConfigurationService;
import org.kinotic.summit42.fakes.FakeLogService;
import org.kinotic.summit42.fakes.FakeMicroserviceDeploymentService;
import org.kinotic.summit42.fakes.FakeOwnedOidcConfigurationService;
import org.kinotic.summit42.fakes.FakeOwnedPasswordConfigurationService;
import org.kinotic.summit42.fakes.FakeProjectService;
import org.kinotic.summit42.fakes.FakeRecipeService;
import org.kinotic.summit42.fakes.RecipeService;
import org.kinotic.summit42.fakes.FakeUiDeploymentService;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.kinotic.summit42.internal.api.repositories.BuilderSessionRepository;
import org.kinotic.summit42.internal.api.repositories.ConversationRepository;
import org.kinotic.system.api.model.llm.LlmConfiguration;
import org.kinotic.system.api.model.llm.LlmProviderType;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Covers the app builder's flows against fakes of the Kinotic services it calls, and a fake of Anthropic's API the
 * system's LLM configuration points at.
 */
public class AppBuilderFlowTests extends AppBuilderServiceTestBase {

    private static final String COMMIT = "c0ffee";

    @Autowired
    private FakeApplicationService applicationService;
    @Autowired
    private FakeProjectService projectService;
    @Autowired
    private FakeOwnedPasswordConfigurationService passwordConfigurationService;
    @Autowired
    private FakeOwnedOidcConfigurationService oidcConfigurationService;
    @Autowired
    private FakeUiDeploymentService uiDeploymentService;
    @Autowired
    private FakeMicroserviceDeploymentService microserviceDeploymentService;
    @Autowired
    private FakeLogService logService;
    @Autowired
    private FakeRecipeService recipeService;
    @Autowired
    private ServiceRegistry serviceRegistry;
    @Autowired
    private FakeLlmConfigurationService llmConfigurationService;
    @Autowired
    private FakeAnthropic anthropic;
    @Autowired
    private FakeGitHub gitHub;
    @Autowired
    private ProjectRepoCredentialService repoCredentialService;
    @Autowired
    private BuilderSessionRepository sessionRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private Summit42Properties properties;

    private OrganizationParticipant participant;

    @BeforeEach
    public void setUp() {
        participant = newOrganizationParticipant();
        anthropic.reset();
        llmConfigurationService.save(llmConfiguration(new Date(0)), "test-key");
    }

    @Test
    public void testFindAppsListsTheAppsWithAProjectByNameWithTheUrlOfTheirServingUi() throws Exception {
        // added out of order, so the order findApps answers in is its own
        Application zeta = applicationService.add(organizationId(), "Zeta", "The last app");
        Application beta = applicationService.add(organizationId(), "beta", null);
        Application alpha = applicationService.add(organizationId(), "Alpha", null);
        applicationService.add(organizationId(), "No Project", null);
        projectService.add(organizationId(), zeta.getId(), RepositoryConnectionStatus.CONNECTED);
        projectService.add(organizationId(), beta.getId(), RepositoryConnectionStatus.CONNECTED);
        projectService.add(organizationId(), alpha.getId(), RepositoryConnectionStatus.CONNECTED);
        uiDeploymentService.add(ui(zeta.getId(), null, "admin", null, "https://admin.zeta.example"));
        uiDeploymentService.add(ui(zeta.getId(), null, "web", COMMIT, "https://zeta.example"));

        List<BuilderApp> apps = await(callAs(participant, service -> service.findApps()));

        Assertions.assertEquals(List.of("Alpha", "beta", "Zeta"), apps.stream().map(BuilderApp::getName).toList());
        Assertions.assertNull(apps.get(0).getUrl());
        Assertions.assertEquals("The last app", apps.get(2).getDescription());
        Assertions.assertEquals("https://zeta.example", apps.get(2).getUrl());
    }

    @Test
    public void testFindAppsReadsEveryPageOfTheOrganizationsApps() throws Exception {
        IntStream.range(0, 150).forEach(i -> {
            Application application = applicationService.add(organizationId(), "App " + i, null);
            projectService.add(organizationId(), application.getId(), RepositoryConnectionStatus.CONNECTED);
        });

        List<BuilderApp> apps = await(callAs(participant, service -> service.findApps()));

        Assertions.assertEquals(150, apps.size());
    }

    @Test
    public void testCreateAppCreatesTheApplicationAndItsProject() throws Exception {
        BuilderApp app = createApp("  Todo List ", "  ");

        Application application = applicationService.applicationsOf(organizationId()).values().iterator().next();
        Assertions.assertEquals("Todo List", application.getName());
        Assertions.assertNull(application.getDescription());
        Assertions.assertEquals(organizationId(), application.getOrganizationId());
        Assertions.assertEquals(TenantManagement.SYSTEM, application.getTenantManagement());
        // the app is the application the application service created, under the id it assigned
        Assertions.assertEquals(application.getId(), app.getApplicationId());
        Assertions.assertEquals("Todo List", app.getName());
        Assertions.assertNull(app.getDescription());
        Assertions.assertNull(app.getUrl());
        Project project = projectService.projectsOf(organizationId()).getFirst();
        Assertions.assertEquals(application.getId(), project.getApplicationId());
        Assertions.assertEquals("Todo List", project.getName());
        Assertions.assertEquals(organizationId(), project.getOrganizationId());
        Assertions.assertEquals(ProjectType.TYPESCRIPT, project.getSourceOfTruth());
        Assertions.assertTrue(project.isRepoPrivate());
        // the app takes its users' passwords, for the people invited to it
        PasswordConfiguration password = passwordOf(application.getId());
        Assertions.assertTrue(password.isEnabled());
        Assertions.assertFalse(password.isCreateUserOnSignIn());
        Assertions.assertNull(password.getTenantId());
    }

    @Test
    public void testCreateAppLetsAnyoneSignUpWhenAsked() throws Exception {
        BuilderApp app = await(createAppAs(participant, "Shop", null, true)).getLast().getApp();

        PasswordConfiguration password = passwordOf(app.getApplicationId());
        Assertions.assertTrue(password.isEnabled());
        Assertions.assertTrue(password.isCreateUserOnSignIn());
    }

    @Test
    public void testCreateAppDeletesTheApplicationWhenItsSignInCannotBeSetUp() {
        passwordConfigurationService.failSaves(organizationId());

        ExecutionException e = Assertions.assertThrows(ExecutionException.class,
                                                       () -> await(createAppAs(participant, "Todo", null)));

        Assertions.assertEquals("The password configuration could not be saved", e.getCause().getMessage());
        Assertions.assertTrue(applicationService.applicationsOf(organizationId()).isEmpty());
        Assertions.assertTrue(projectService.projectsOf(organizationId()).isEmpty());
    }

    @Test
    public void testCreateAppDescribesTheAppWithClaudesSummaryOfWhatTheUserWants() throws Exception {
        String wanted = "Customers book grooming appointments for their dogs. Staff see the day's bookings, and each"
                        + " customer gets a reminder the day before.";
        anthropic.replyWithText("Books dog grooming ", "appointments and reminds customers.\n");

        BuilderApp app = createApp("Grooming", wanted);

        String summary = "Books dog grooming appointments and reminds customers.";
        Assertions.assertEquals(summary, app.getDescription());
        Assertions.assertEquals(summary, applicationService.applicationsOf(organizationId()).values().iterator().next().getDescription());
        Assertions.assertEquals(summary, projectService.projectsOf(organizationId()).getFirst().getDescription());
        Assertions.assertEquals(List.of(wanted), textsOf(anthropic.requests().getFirst()));
    }

    @Test
    public void testCreateAppReportsEachStepAsItStartsThenTheApp() throws Exception {
        anthropic.replyWithText("Books dog grooming appointments.");

        List<AppCreationEvent> described = await(createAppAs(participant, "Grooming", "Customers book grooming"));
        List<AppCreationEvent> undescribed = await(createAppAs(participant, "Todo", null));

        Assertions.assertEquals(List.of(AppCreationEventType.DESCRIBING,
                                        AppCreationEventType.CREATING_APPLICATION,
                                        AppCreationEventType.CREATING_REPOSITORY,
                                        AppCreationEventType.CREATED),
                                described.stream().map(AppCreationEvent::getType).toList());
        Assertions.assertEquals(List.of(AppCreationEventType.CREATING_APPLICATION,
                                        AppCreationEventType.CREATING_REPOSITORY,
                                        AppCreationEventType.CREATED),
                                undescribed.stream().map(AppCreationEvent::getType).toList());
        Assertions.assertTrue(described.subList(0, 3).stream().allMatch(event -> event.getApp() == null));
        Assertions.assertEquals("Grooming", described.getLast().getApp().getName());
        Assertions.assertEquals("Todo", undescribed.getLast().getApp().getName());
    }

    @Test
    public void testCreateAppWhoseDescriptionCannotBeSummarizedHasNone() throws Exception {
        anthropic.replyWithError(400);

        BuilderApp app = createApp("Grooming", "Customers book grooming");

        Assertions.assertEquals("Grooming", app.getName());
        Assertions.assertNull(app.getDescription());
        Assertions.assertNull(applicationService.applicationsOf(organizationId()).values().iterator().next().getDescription());
    }

    @Test
    public void testCreateAppDeletesTheApplicationWhenItsProjectCannotBeCreated() {
        projectService.failCreations(organizationId());

        ExecutionException e = Assertions.assertThrows(ExecutionException.class,
                                                       () -> await(createAppAs(participant, "Todo", null)));

        Assertions.assertEquals("The repository could not be created", e.getCause().getMessage());
        Assertions.assertTrue(applicationService.applicationsOf(organizationId()).isEmpty());
    }

    @Test
    public void testCreateAppRendersARepositoryWhoseInitializationFailedAgain() throws Exception {
        projectService.createWith(organizationId(), RepositoryConnectionStatus.INITIALIZATION_FAILED);

        BuilderApp app = createApp("Todo", null);

        Assertions.assertEquals("Todo", app.getName());
        Assertions.assertEquals(RepositoryConnectionStatus.CONNECTED,
                                projectService.projectsOf(organizationId()).getFirst().getRepoConnectionStatus());
    }

    @Test
    public void testCreateAppFailsWhenTheRepositoryCannotBeRenderedAgain() {
        projectService.createWith(organizationId(), RepositoryConnectionStatus.INITIALIZATION_FAILED);
        projectService.failRetries(organizationId());

        ExecutionException e = Assertions.assertThrows(ExecutionException.class,
                                                       () -> await(createAppAs(participant, "Todo", null)));

        Assertions.assertEquals("The template could not be rendered", e.getCause().getMessage());
    }

    @Test
    public void testChatAboutAnAppThatNoLongerExistsAnswersWithAnError() {
        Assertions.assertEquals(List.of("This app no longer exists"), errorsOf(chat("todo", "Add a due date")));
    }

    @Test
    public void testChatAboutAnAppWhoseRepositoryIsDisconnectedAnswersWithAnError() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.DISCONNECTED);

        List<String> errors = errorsOf(chat("todo", "Add a due date"));

        Assertions.assertEquals(1, errors.size());
        Assertions.assertTrue(errors.getFirst().startsWith("Summit 42 can no longer reach this app's GitHub repository."),
                              errors.getFirst());
    }

    @Test
    public void testChatWhileATurnRunsAnswersWithAnError() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        sessionOf("todo").setTurnRunning(true);

        Assertions.assertEquals(List.of("Claude is still working on your last message"),
                                errorsOf(chat("todo", "Add a due date")));
        Assertions.assertTrue(anthropic.requests().isEmpty());
    }

    @Test
    public void testChatWithoutTheLlmConfigurationAnswersWithAnErrorAndEndsTheTurn() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        llmConfigurationService.delete(properties.getLlmConfigurationName());

        List<String> errors = errorsOf(chat("todo", "Add a due date"));

        Assertions.assertEquals(List.of("No LLM configuration is named 'summit-42'. Add it on the system console's"
                                        + " LLM configurations page."), errors);
        Assertions.assertFalse(sessionOf("todo").isTurnRunning());
    }

    @Test
    public void testChatWithAnLlmConfigurationWithoutAnApiKeyAnswersWithAnError() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        llmConfigurationService.save(llmConfiguration(new Date()), null);

        Assertions.assertEquals(List.of("The LLM configuration 'summit-42' holds no API key"),
                                errorsOf(chat("todo", "Add a due date")));
    }

    @Test
    public void testChatStreamsTheReplyAndRecordsTheExchange() throws Exception {
        Project project = projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        project.setName("Todo");
        project.setDescription("Tracks my chores");
        anthropic.replyWithText("Your todo app ", "is ready");

        List<BuildEvent> events = chat("todo", "Build a todo app");
        Conversation conversation = await(callAs(participant, service -> service.findConversation("todo")));

        Assertions.assertEquals(List.of("Your todo app ", "is ready"),
                                events.stream().filter(event -> event.getType() == BuildEventType.TEXT).map(BuildEvent::getText).toList());
        Assertions.assertEquals(List.of("Build a todo app", "Your todo app is ready"),
                                conversation.getMessages().stream().map(ChatMessage::getText).toList());
        Assertions.assertFalse(sessionOf("todo").isTurnRunning());
        // saved, so the conversation outlives the server
        await(sessionOf("todo").getLastSave().toCompletionStage().toCompletableFuture());
        Conversation saved = await(conversationRepository.findById(new ApplicationKey(organizationId(), "todo"))
                                                         .toCompletionStage().toCompletableFuture());
        Assertions.assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT),
                                saved.getMessages().stream().map(ChatMessage::getRole).toList());
        Assertions.assertEquals(List.of("Build a todo app", "Your todo app is ready"),
                                saved.getMessages().stream().map(ChatMessage::getText).toList());
        String system = systemOf(anthropic.requests().getFirst());
        Assertions.assertTrue(system.contains("The user named this app 'Todo' and described it as: Tracks my chores."), system);
        Assertions.assertEquals(List.of("Build a todo app"), textsOf(anthropic.requests().getFirst()));
    }

    @Test
    public void testChatReportsEachToolCallAsAStepBetweenTheParagraphsOfTheReply() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithTextAndToolCall("Let me read the skill first.", "readSkill", new JsonObject().put("skill", "frontend"));
        anthropic.replyWithTextAndToolCall("Now its Vite notes.", "readSkill",
                                           new JsonObject().put("skill", "frontend").put("file", "references/vite.md"));
        anthropic.replyWithText("Your todo app ", "is ready");

        List<BuildEvent> events = chat("todo", "Build a todo app");
        Conversation conversation = await(callAs(participant, service -> service.findConversation("todo")));

        Assertions.assertEquals(List.of("TEXT null Let me read the skill first.",
                                        "PROGRESS READING Reading the frontend skill",
                                        "TEXT null Now its Vite notes.",
                                        "PROGRESS READING Reading references/vite.md of the frontend skill",
                                        "TEXT null Your todo app ",
                                        "TEXT null is ready"),
                                events.stream().map(event -> event.getType() + " " + event.getStepType() + " " + event.getText()).toList());
        Assertions.assertEquals("Let me read the skill first.\n\nNow its Vite notes.\n\nYour todo app is ready",
                                conversation.getMessages().getLast().getText());
    }

    @Test
    public void testChatWaitsForAReplyThatStreamsForMoreThanAMinute() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        // the providers' clients end a call after 60 seconds unless told otherwise
        anthropic.replyWithTextAfterPause("Writing your files", Duration.ofSeconds(65), ", done");

        List<BuildEvent> events = chatAs(participant, "todo", "Build a todo app").collectList().block(Duration.ofSeconds(100));

        Assertions.assertEquals(List.of("Writing your files", ", done"),
                                events.stream().map(BuildEvent::getText).toList());
    }

    @Test
    public void testATurnRunsToItsEndAfterItsChatGoesAwayAndIsFollowedFromItsFirstEvent() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithTextAfterPause("Writing your files", Duration.ofSeconds(3), ", done");

        // the chat that sent the message goes away after the first chunk
        BuildEvent first = chatAs(participant, "todo", "Build a todo app").blockFirst(TIMEOUT);
        Assertions.assertEquals("Writing your files", first.getText());

        List<BuildEvent> followed = followAs("todo").collectList().block(TIMEOUT);
        Conversation conversation = await(callAs(participant, service -> service.findConversation("todo")));

        Assertions.assertEquals(List.of("Writing your files", ", done"), followed.stream().map(BuildEvent::getText).toList());
        Assertions.assertEquals(first.getTimestamp(), followed.getFirst().getTimestamp());
        Assertions.assertEquals(List.of("Build a todo app", "Writing your files, done"),
                                conversation.getMessages().stream().map(ChatMessage::getText).toList());
        Assertions.assertFalse(sessionOf("todo").isTurnRunning());
    }

    @Test
    public void testAMessageSentDuringAStepIsReadAfterItsToolCalls() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithTextAndToolCallAfterPause("Let me read the skill first.", Duration.ofSeconds(2), "readSkill",
                                                     new JsonObject().put("skill", "frontend"));
        anthropic.replyWithText("Red it is.");

        Flux<BuildEvent> turn = chatAs(participant, "todo", "Build a todo app").replay().autoConnect();
        Assertions.assertEquals("Let me read the skill first.", turn.blockFirst(TIMEOUT).getText());
        List<BuildEvent> steered = chat("todo", "Make it red");
        List<BuildEvent> events = turn.collectList().block(TIMEOUT);
        Conversation conversation = await(callAs(participant, service -> service.findConversation("todo")));

        Assertions.assertEquals(List.of(), steered);
        Assertions.assertEquals(List.of("TEXT Let me read the skill first.",
                                        "PROGRESS Reading the frontend skill",
                                        "USER_MESSAGE Make it red",
                                        "TEXT Red it is."),
                                events.stream().map(event -> event.getType() + " " + event.getText()).toList());
        // the message follows the tool's result in the step Claude reads it in
        JsonArray sent = anthropic.requests().get(1).getJsonArray("messages");
        Assertions.assertEquals("tool_result", sent.getJsonObject(sent.size() - 2).getJsonArray("content").getJsonObject(0).getString("type"));
        Assertions.assertEquals("Make it red", textOf(sent.getJsonObject(sent.size() - 1).getValue("content")));
        Assertions.assertEquals(List.of("Build a todo app", "Let me read the skill first.", "Make it red", "Red it is."),
                                conversation.getMessages().stream().map(ChatMessage::getText).toList());
        Assertions.assertFalse(sessionOf("todo").isTurnRunning());
    }

    @Test
    public void testAMessageSentAfterTheLastStepGetsAnAnswerOfItsOwnInTheSameTurn() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithTextAfterPause("Your todo app ", Duration.ofSeconds(2), "is ready");
        anthropic.replyWithText("It is blue now.");

        Flux<BuildEvent> turn = chatAs(participant, "todo", "Build a todo app").replay().autoConnect();
        Assertions.assertEquals("Your todo app ", turn.blockFirst(TIMEOUT).getText());
        List<BuildEvent> steered = chat("todo", "Make it blue");
        List<BuildEvent> events = turn.collectList().block(TIMEOUT);
        Conversation conversation = await(callAs(participant, service -> service.findConversation("todo")));

        Assertions.assertEquals(List.of(), steered);
        Assertions.assertEquals(List.of("TEXT Your todo app ", "TEXT is ready", "USER_MESSAGE Make it blue", "TEXT It is blue now."),
                                events.stream().map(event -> event.getType() + " " + event.getText()).toList());
        Assertions.assertEquals(List.of("Build a todo app", "Your todo app is ready", "Make it blue"),
                                textsOf(anthropic.requests().get(1)));
        Assertions.assertEquals(List.of("Build a todo app", "Your todo app is ready", "Make it blue", "It is blue now."),
                                conversation.getMessages().stream().map(ChatMessage::getText).toList());
        Assertions.assertFalse(sessionOf("todo").isTurnRunning());
    }

    @Test
    public void testFollowRunningTurnCompletesAtOnceWhenNoTurnRuns() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithText("Done");
        chat("todo", "Build a todo app");

        Assertions.assertEquals(List.of(), followAs("todo").collectList().block(TIMEOUT));
    }

    @Test
    public void testFollowRunningTurnOfAnAppThatNoLongerExistsAnswersWithAnError() {
        Assertions.assertEquals(List.of("This app no longer exists"), errorsOf(followAs("gone").collectList().block(TIMEOUT)));
    }

    @Test
    public void testChatSendsTheMostRecentMessagesStartingWithAUserMessage() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        List<ChatMessage> history = sessionOf("todo").getConversation().getMessages();
        IntStream.range(0, 41).forEach(i -> history.add(new ChatMessage(i % 2 == 0 ? ChatRole.USER : ChatRole.ASSISTANT,
                                                                       "message " + i)));
        anthropic.replyWithText("Done");

        chat("todo", "Add a due date");

        // the window of the 40 most recent messages starts with a reply, which is dropped with the message it answered
        List<String> sent = textsOf(anthropic.requests().getFirst());
        Assertions.assertEquals(40, sent.size());
        Assertions.assertEquals("message 2", sent.getFirst());
        Assertions.assertEquals("Add a due date", sent.getLast());
    }

    @Test
    public void testChatEndsWithAnErrorWhenTheLlmRefusesTheRequest() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithError(400);

        List<String> errors = errorsOf(chat("todo", "Build a todo app"));
        Conversation conversation = await(callAs(participant, service -> service.findConversation("todo")));

        Assertions.assertEquals(List.of("Something went wrong. Please try again."), errors);
        Assertions.assertEquals(List.of(ChatRole.USER), conversation.getMessages().stream().map(ChatMessage::getRole).toList());
        Assertions.assertFalse(sessionOf("todo").isTurnRunning());
    }

    @Test
    public void testChatReusesTheLlmClientUntilTheConfigurationChanges() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithText("One");
        anthropic.replyWithText("Two");
        anthropic.replyWithText("Three");

        // a client for the configuration as setUp saved it, whether built now or by an earlier test
        chat("todo", "First");
        int readsBeforeChange = llmConfigurationService.apiKeyReads();
        chat("todo", "Second");
        int readsUnchanged = llmConfigurationService.apiKeyReads();
        llmConfigurationService.save(llmConfiguration(new Date()), "test-key");
        chat("todo", "Third");

        Assertions.assertEquals(readsBeforeChange, readsUnchanged);
        Assertions.assertEquals(readsBeforeChange + 1, llmConfigurationService.apiKeyReads());
    }

    @Test
    public void testReadSignInTellsClaudeWhoCanSignInAndConfigureSignInChangesIt() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        passwordConfigurationService.add(organizationId(), "todo", false);
        oidcConfigurationService.add(organizationId(), "todo", "Google", OidcProviderType.GOOGLE);
        oidcConfigurationService.add(organizationId(), "other", "Okta", OidcProviderType.OKTA);
        anthropic.replyWithToolCall("readSignIn");
        anthropic.replyWithToolCall("configureSignIn", new JsonObject().put("openSignUp", true));
        anthropic.replyWithText("Anyone can sign up now");

        List<BuildEvent> events = chat("todo", "Let my customers sign up");

        Assertions.assertEquals("Only the people the user invites on the app's Users page in Summit 42 can sign in,"
                                + " with an email and a password or through Google.",
                                Json.decodeValue(toolResultOf(anthropic.requests().get(1))));
        Assertions.assertTrue(events.stream().anyMatch(event -> event.getStepType() == BuildStepType.READING
                                                                && "Checking who can sign in".equals(event.getText())), events.toString());
        Assertions.assertTrue(events.stream().anyMatch(event -> event.getStepType() == BuildStepType.CONFIGURING
                                                                && "Letting anyone sign up".equals(event.getText())), events.toString());
        Assertions.assertEquals("Anyone can sign up at the app with an email and a password or through Google.",
                                Json.decodeValue(toolResultOf(anthropic.requests().getLast())));
        Assertions.assertTrue(passwordOf("todo").isCreateUserOnSignIn());
        // every provider of the app's login says the same, and another app's is left alone
        Map<String, Boolean> signsUp = oidcConfigurationService.configurationsOf(organizationId()).values().stream()
                                                               .collect(Collectors.toMap(OwnedOidcConfiguration::getName,
                                                                                                          OwnedOidcConfiguration::isCreateUserOnSignIn));
        Assertions.assertEquals(Map.of("Google", true, "Okta", false), signsUp);
    }

    @Test
    public void testSetOpenSignUpKeepsEveryProviderOfTheAppSayingTheSame() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        passwordConfigurationService.add(organizationId(), "todo", false);
        oidcConfigurationService.add(organizationId(), "todo", "Google", OidcProviderType.GOOGLE);

        await(callAs(participant, service -> service.setOpenSignUp("todo", true)));

        Assertions.assertTrue(passwordOf("todo").isCreateUserOnSignIn());
        Assertions.assertTrue(oidcConfigurationService.configurationsOf(organizationId()).values().stream()
                                                      .allMatch(OwnedOidcConfiguration::isCreateUserOnSignIn));
    }

    @Test
    public void testSetOpenSignUpIsRefusedWhileTheAppOffersNoWayToSignIn() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);

        ExecutionException e = Assertions.assertThrows(ExecutionException.class,
                                                       () -> await(callAs(participant, service -> service.setOpenSignUp("todo", true))));

        Assertions.assertTrue(e.getCause().getMessage().startsWith("The app offers no way to sign in yet."), e.getCause().getMessage());
    }

    @Test
    public void testReadSignInSaysWhenNobodyCanSignInYet() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithToolCall("readSignIn");
        anthropic.replyWithText("Nobody yet");

        chat("todo", "Who can sign in?");

        Assertions.assertEquals("Nobody can sign in to the app yet: the user adds a way to sign in on the app's Settings page in Summit 42.",
                                Json.decodeValue(toolResultOf(anthropic.requests().getLast())));
    }

    @Test
    public void testChatListsTheKinoticSkillsWithADescription() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithText("Done");

        chat("todo", "Build a todo app");

        String system = systemOf(anthropic.requests().getFirst());
        Assertions.assertTrue(system.contains("The Kinotic skills readSkill reads:\n"
                                              + "- frontend: How a UI signs in and connects to Kinotic.\n"
                                              + "- services: How to publish a service."), system);
        Assertions.assertFalse(system.contains("create-app"), system);
        Assertions.assertFalse(system.contains("notes"), system);
    }

    @Test
    public void testReadSkillReadsTheSkillWithoutItsFrontmatterAndNamesItsOtherFiles() {
        String toolResult = readSkill(new JsonObject().put("skill", "frontend"));

        Assertions.assertTrue(toolResult.startsWith("# Frontends\n\nSign in with login(), then connect."), toolResult);
        Assertions.assertFalse(toolResult.contains("description:"), toolResult);
        Assertions.assertTrue(toolResult.endsWith("The skill's other files, which readSkill reads:\nreferences/vite.md"),
                              toolResult);
    }

    @Test
    public void testReadSkillReadsAReferenceOfTheSkill() {
        String toolResult = readSkill(new JsonObject().put("skill", "frontend").put("file", "references/vite.md"));

        Assertions.assertTrue(toolResult.startsWith("# Vite\n\nAlias the domain package to its source."), toolResult);
        Assertions.assertTrue(toolResult.endsWith("The skill's other files, which readSkill reads:\nSKILL.md"), toolResult);
    }

    @Test
    public void testReadSkillOfASkillLeftOutNamesTheSkills() {
        String toolResult = readSkill(new JsonObject().put("skill", "create-app"));

        Assertions.assertEquals("Unknown skill. The skills are: frontend, services", toolResult);
    }

    @Test
    public void testChatAsksAnthropicToCacheThePromptUpToTheLastToolResult() {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithToolCall("readSkill", new JsonObject().put("skill", "frontend"));
        anthropic.replyWithText("Read it");

        chat("todo", "Build a todo app");

        JsonArray messages = anthropic.requests().getLast().getJsonArray("messages");
        JsonArray toolResults = messages.getJsonObject(messages.size() - 1).getJsonArray("content");
        Assertions.assertEquals("ephemeral", toolResults.getJsonObject(toolResults.size() - 1)
                                                        .getJsonObject("cache_control", new JsonObject())
                                                        .getString("type"), toolResults.encode());
    }

    @Test
    public void testReadFilesReadsEachFileUnderAHeaderInTheOrderAsked() {
        appRepository(Map.of("packages/ui/src/App.vue", "<h1>Todos</h1>", "README.md", "# Todo"));
        anthropic.replyWithToolCall("readFiles", new JsonObject().put("paths", new JsonArray().add("packages/ui/src/App.vue")
                                                                                             .add("missing.ts")
                                                                                             .add("README.md")));
        anthropic.replyWithText("Read them");

        chat("todo", "What is in the app?");

        Assertions.assertEquals("=== packages/ui/src/App.vue ===\n<h1>Todos</h1>\n=== missing.ts ===\n(no such file)\n=== README.md ===\n# Todo\n",
                                Json.decodeValue(toolResultOf(anthropic.requests().getLast())));
    }

    @Test
    public void testWriteFilesCommitsEditsAndWholeFilesInOneCommit() {
        appRepository(Map.of("packages/ui/src/App.vue", "<h1>Todos</h1>\n<p style=\"color: blue\">Due soon</p>\n",
                             "README.md", "# Todo"));
        anthropic.replyWithToolCall("writeFiles", new JsonObject()
                .put("message", "Make it red")
                .put("edits", new JsonArray().add(edit("packages/ui/src/App.vue", "color: blue", "color: red"))
                                             .add(edit("packages/ui/src/App.vue", "<h1>Todos</h1>", "<h1>My todos</h1>")))
                .put("files", new JsonArray().add(new JsonObject().put("path", "packages/ui/src/theme.css").put("content", "h1 { color: red; }"))
                                             .add(new JsonObject().put("path", "README.md"))));
        anthropic.replyWithText("Done");

        List<BuildEvent> events = chat("todo", "Make it red");

        Assertions.assertEquals(Map.of("packages/ui/src/App.vue", "<h1>My todos</h1>\n<p style=\"color: red\">Due soon</p>\n",
                                       "packages/ui/src/theme.css", "h1 { color: red; }"),
                                gitHub.appFiles());
        Assertions.assertEquals(List.of("Make it red"), gitHub.commitMessages());
        Assertions.assertTrue(events.stream().anyMatch(event -> "Committing 3 files: Make it red".equals(event.getText())), events.toString());
        String toolResult = toolResultOf(anthropic.requests().getLast());
        Assertions.assertTrue(toolResult.contains("its deployment has started"), toolResult);
    }

    @Test
    public void testWriteFilesCommitsNothingWhenTheTextOfAnEditIsNotFoundExactlyOnce() {
        Map<String, String> files = Map.of("packages/ui/src/App.vue", "<p>blue</p><p>blue</p>");
        appRepository(files);
        anthropic.replyWithToolCall("writeFiles", new JsonObject()
                .put("message", "Make it green")
                .put("edits", new JsonArray().add(edit("packages/ui/src/App.vue", "green", "red"))));
        anthropic.replyWithToolCall("writeFiles", new JsonObject()
                .put("message", "Make it red")
                .put("files", new JsonArray().add(new JsonObject().put("path", "packages/ui/src/theme.css").put("content", "")))
                .put("edits", new JsonArray().add(edit("packages/ui/src/App.vue", "blue", "red"))));
        anthropic.replyWithText("I could not change it");

        chat("todo", "Make it red");

        String notFound = toolResultOf(anthropic.requests().get(1));
        Assertions.assertTrue(notFound.contains("The text to replace in packages/ui/src/App.vue was not found"), notFound);
        String twice = toolResultOf(anthropic.requests().get(2));
        Assertions.assertTrue(twice.contains("The text to replace in packages/ui/src/App.vue occurs more than once"), twice);
        Assertions.assertEquals(files, gitHub.appFiles());
        Assertions.assertEquals(List.of(), gitHub.commitMessages());
    }

    @Test
    public void testWaitForDeploymentReportsTheServingUiAndTheAppsUrl() {
        Project project = deployedProject(new DeploymentState(DeploymentStatusType.READY, COMMIT), null);
        uiDeploymentService.add(ui("todo", project.getId(), "web", COMMIT, "https://todo.example"));
        anthropic.replyWithToolCall("waitForDeployment");
        anthropic.replyWithText("Your app is live");

        List<BuildEvent> events = chat("todo", "Is it deployed?");

        Assertions.assertTrue(events.stream().anyMatch(event -> event.getType() == BuildEventType.PROGRESS
                                                                && event.getStepType() == BuildStepType.DEPLOYING
                                                                && "Deploying: ready".equals(event.getText())), events.toString());
        Assertions.assertEquals(List.of("https://todo.example"),
                                events.stream().filter(event -> event.getType() == BuildEventType.APP_READY).map(BuildEvent::getUrl).toList());
        String toolResult = toolResultOf(anthropic.requests().getLast());
        Assertions.assertTrue(toolResult.contains("The deployment is running commit " + COMMIT), toolResult);
        Assertions.assertTrue(toolResult.contains("UI web: serving at https://todo.example"), toolResult);
    }

    @Test
    public void testWaitForDeploymentReportsWhyTheDeploymentFailed() {
        // a failed deployment observes the commit it still serves, not the one that failed
        deployedProject(new DeploymentState(DeploymentStatusType.FAILED, "previous"), "vite build failed");
        anthropic.replyWithToolCall("waitForDeployment");
        anthropic.replyWithText("I'll fix the build");

        List<BuildEvent> events = chat("todo", "Is it deployed?");

        Assertions.assertTrue(events.stream().noneMatch(event -> event.getType() == BuildEventType.APP_READY), events.toString());
        String toolResult = toolResultOf(anthropic.requests().getLast());
        Assertions.assertTrue(toolResult.contains("The deployment failed: vite build failed"), toolResult);
    }

    @Test
    public void testReadAppLogsReadsTheLastHourOfEachMicroserviceOldestFirst() {
        Project project = projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        microserviceDeploymentService.add(microservice(project, "worker", null, "exited with code 1"));
        microserviceDeploymentService.add(microservice(project, "main", "workload-main", null));
        Instant now = Instant.now();
        logService.log(organizationId(), "workload-main", now.minus(Duration.ofMinutes(5)), "fetch failed: connect ECONNREFUSED");
        logService.log(organizationId(), "workload-main", now.minus(Duration.ofMinutes(30)), "main microservice running");
        logService.log(organizationId(), "workload-main", now.minus(Duration.ofHours(2)), "an earlier run");
        anthropic.replyWithToolCall("readAppLogs");
        anthropic.replyWithText("The search cannot reach the sites");

        List<BuildEvent> events = chat("todo", "Why are there only two results?");

        Assertions.assertTrue(events.stream().anyMatch(event -> event.getStepType() == BuildStepType.CHECKING
                                                                && "Reading the app's logs".equals(event.getText())), events.toString());
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
        Assertions.assertEquals("=== main ===\n"
                                + time.format(now.minus(Duration.ofMinutes(30))) + " main microservice running\n"
                                + time.format(now.minus(Duration.ofMinutes(5))) + " fetch failed: connect ECONNREFUSED\n"
                                + "=== worker ===\n"
                                + "Not running as it should: exited with code 1\n"
                                + "(not started yet)\n",
                                Json.decodeValue(toolResultOf(anthropic.requests().getLast())));
    }

    @Test
    public void testCallAppServiceCallsTheFunctionOfTheAppsServiceAsTheUser() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        ServiceIdentifier recipes = publishRecipeService("todo");
        try {
            anthropic.replyWithToolCall("callAppService", new JsonObject().put("service", "recipeapp.RecipeService")
                                                                          .put("function", "searchRecipes")
                                                                          .put("arguments", "[\"enchiladas\", 3]"));
            anthropic.replyWithText("It finds three");

            List<BuildEvent> events = chat("todo", "Does the search work?");

            Assertions.assertTrue(events.stream().anyMatch(event -> event.getStepType() == BuildStepType.CHECKING
                                                                    && "Testing the app".equals(event.getText())), events.toString());
            Assertions.assertEquals(new JsonArray().add("enchiladas 1").add("enchiladas 2").add("enchiladas 3"),
                                    Json.decodeValue(toolResultOf(anthropic.requests().getLast())));
            Assertions.assertEquals(participant.getId(), recipeService.lastCaller().getId());
        } finally {
            await(serviceRegistry.unregister(recipes).toCompletionStage().toCompletableFuture());
        }
    }

    @Test
    public void testCallAppServiceReportsTheErrorOfTheFunctionAndAServiceTheAppDoesNotPublish() throws Exception {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        // published by another app of the organization, which the todo app's calls do not reach
        ServiceIdentifier otherApp = publishRecipeService("other");
        ServiceIdentifier recipes = publishRecipeService("todo");
        try {
            anthropic.replyWithToolCall("callAppService", new JsonObject().put("service", "recipeapp.RecipeService")
                                                                          .put("function", "searchTheWeb")
                                                                          .put("arguments", "[\"enchiladas\"]"));
            anthropic.replyWithToolCall("callAppService", new JsonObject().put("service", "recipeapp.MealService")
                                                                          .put("function", "searchMeals")
                                                                          .put("arguments", "[]"));
            anthropic.replyWithToolCall("callAppService", new JsonObject().put("service", "app.other~recipeapp.RecipeService")
                                                                          .put("function", "searchRecipes")
                                                                          .put("arguments", "[\"enchiladas\", 1]"));
            anthropic.replyWithText("The web search fails");

            chat("todo", "Does the search work?");

            String failed = toolResultOf(anthropic.requests().get(1));
            Assertions.assertTrue(failed.contains("The function failed: fetch failed: connect ECONNREFUSED"), failed);
            String missing = toolResultOf(anthropic.requests().get(2));
            Assertions.assertTrue(missing.contains("No microservice of the app publishes recipeapp.MealService"), missing);
            String otherZone = toolResultOf(anthropic.requests().get(3));
            Assertions.assertTrue(otherZone.contains("The service is named by its namespace and name"), otherZone);
        } finally {
            await(serviceRegistry.unregister(recipes).toCompletionStage().toCompletableFuture());
            await(serviceRegistry.unregister(otherApp).toCompletionStage().toCompletableFuture());
        }
    }

    // publishes the recipe service in the zone of the organization's app, as the app's microservice would
    private ServiceIdentifier publishRecipeService(String applicationId) throws Exception {
        ServiceIdentifier ret = new ServiceIdentifier(DomainUtil.applicationZone(organizationId(), applicationId),
                                                      "recipeapp", "RecipeService", null, "0.1.0");
        await(serviceRegistry.register(ret, RecipeService.class, recipeService).toCompletionStage().toCompletableFuture());
        return ret;
    }

    private MicroserviceDeployment microservice(Project project, String name, String workloadId, String failureMessage) {
        return new MicroserviceDeployment().setOrganizationId(organizationId())
                                           .setApplicationId(project.getApplicationId())
                                           .setProjectId(project.getId())
                                           .setName(name)
                                           .setWorkloadId(workloadId)
                                           .setFailureMessage(failureMessage);
    }

    // the todo app's project, whose repository on GitHub holds the files given
    private void appRepository(Map<String, String> files) {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        gitHub.resetAppRepository(files);
        Mockito.when(repoCredentialService.issueWriteToken(Mockito.anyString()))
               .thenReturn(Future.succeededFuture(new ProjectRepoToken("repo-token", Instant.now().plus(Duration.ofHours(1)),
                                                                       "https://github.com/" + FakeGitHub.APP_REPOSITORY + ".git",
                                                                       FakeGitHub.REF)));
    }

    private static JsonObject edit(String path, String oldText, String newText) {
        return new JsonObject().put("path", path).put("oldText", oldText).put("newText", newText);
    }

    private String organizationId() {
        return participant.getOrganizationId();
    }

    // the email and password of the organization's app
    private PasswordConfiguration passwordOf(String applicationId) {
        PasswordConfiguration ret = passwordConfigurationService.configurationsOf(organizationId())
                                                                .get(PasswordConfiguration.idOf(organizationId(), applicationId, null));
        Assertions.assertNotNull(ret, "The app " + applicationId + " takes no password");
        return ret;
    }

    // the text of a turn's readSkill call with the input given, which Spring AI sends Claude as a JSON string
    private String readSkill(JsonObject input) {
        projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        anthropic.replyWithToolCall("readSkill", input);
        anthropic.replyWithText("Read it");

        chat("todo", "Build a todo app");

        return (String) Json.decodeValue(toolResultOf(anthropic.requests().getLast()));
    }

    private BuilderSession sessionOf(String applicationId) {
        return sessionRepository.findById(new ApplicationKey(organizationId(), applicationId))
                                .toCompletionStage().toCompletableFuture().join();
    }

    private List<BuildEvent> chat(String applicationId, String message) {
        return chatAs(participant, applicationId, message).collectList().block(TIMEOUT);
    }

    private Flux<BuildEvent> followAs(String applicationId) {
        return streamAs(participant, service -> service.followRunningTurn(applicationId));
    }

    // the app the last event of createApp carries
    private BuilderApp createApp(String name, String description) throws Exception {
        return await(createAppAs(participant, name, description)).getLast().getApp();
    }

    private static List<String> errorsOf(List<BuildEvent> events) {
        return events.stream().filter(event -> event.getType() == BuildEventType.ERROR).map(BuildEvent::getText).toList();
    }

    private LlmConfiguration llmConfiguration(Date updated) {
        return new LlmConfiguration().setId("llm")
                                     .setName(properties.getLlmConfigurationName())
                                     .setProvider(LlmProviderType.ANTHROPIC)
                                     .setModel("claude-test")
                                     .setEndpoint(anthropic.baseUrl())
                                     .setApiKeyHeld(true)
                                     .setUpdated(updated);
    }

    // the todo app's project, whose session last committed COMMIT, with a deployment that has finished deploying it
    private Project deployedProject(DeploymentState observed, String failureMessage) {
        Project ret = projectService.add(organizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        ProjectDeployment deployment = new ProjectDeployment().setFailureMessage(failureMessage);
        deployment.getState()
                  .setDesired(new DeploymentState(DeploymentStatusType.READY, COMMIT))
                  .setObserved(observed)
                  .setGeneration(1)
                  .setObservedGeneration(1);
        projectService.setDeployment(ret.getId(), deployment);
        sessionOf("todo").setLastCommitSha(COMMIT);
        return ret;
    }

    private UiDeployment ui(String applicationId, String projectId, String name, String servedCommit, String url) {
        UiDeployment ret = new UiDeployment().setOrganizationId(organizationId())
                                             .setApplicationId(applicationId)
                                             .setProjectId(projectId)
                                             .setName(name)
                                             .setUrl(url);
        ret.getState()
           .setDesired(new DeploymentState(DeploymentStatusType.READY, servedCommit))
           .setObserved(servedCommit != null ? new DeploymentState(DeploymentStatusType.READY, servedCommit) : null)
           .setGeneration(1)
           .setObservedGeneration(1);
        return ret;
    }

    // the request's system prompt, sent as a string or as text blocks
    private static String systemOf(JsonObject request) {
        Object system = request.getValue("system");
        String ret;
        if (system instanceof JsonArray blocks) {
            ret = blocks.stream().map(block -> ((JsonObject) block).getString("text")).reduce("", String::concat);
        } else {
            ret = String.valueOf(system);
        }
        return ret;
    }

    // the text of each message of the request, in order
    private static List<String> textsOf(JsonObject request) {
        return request.getJsonArray("messages").stream()
                      .map(message -> textOf(((JsonObject) message).getValue("content")))
                      .toList();
    }

    private static String textOf(Object content) {
        String ret;
        if (content instanceof JsonArray blocks) {
            ret = blocks.stream()
                        .map(block -> (JsonObject) block)
                        .filter(block -> "text".equals(block.getString("type")))
                        .map(block -> block.getString("text"))
                        .reduce("", String::concat);
        } else {
            ret = String.valueOf(content);
        }
        return ret;
    }

    // the content of the tool result the request answers the last tool call with
    private static String toolResultOf(JsonObject request) {
        JsonArray messages = request.getJsonArray("messages");
        JsonArray content = messages.getJsonObject(messages.size() - 1).getJsonArray("content");
        JsonObject toolResult = content.stream()
                                       .map(block -> (JsonObject) block)
                                       .filter(block -> "tool_result".equals(block.getString("type")))
                                       .findFirst()
                                       .orElseThrow();
        return textOf(toolResult.getValue("content"));
    }
}
