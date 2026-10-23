package org.kinotic.summit42.internal.api.services;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.model.TenantManagement;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.ProjectType;
import org.kinotic.management.api.model.RepositoryConnectionStatus;
import org.kinotic.management.api.model.deployment.DeploymentState;
import org.kinotic.management.api.model.deployment.DeploymentStatusType;
import org.kinotic.management.api.model.deployment.ProjectDeployment;
import org.kinotic.management.api.model.deployment.UiDeployment;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuildEventType;
import org.kinotic.summit42.api.model.BuildStepType;
import org.kinotic.summit42.api.model.BuilderApp;
import org.kinotic.summit42.api.model.ChatMessage;
import org.kinotic.summit42.api.model.ChatRole;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.fakes.FakeAnthropic;
import org.kinotic.summit42.fakes.FakeApplicationService;
import org.kinotic.summit42.fakes.FakeLlmConfigurationService;
import org.kinotic.summit42.fakes.FakeProjectService;
import org.kinotic.summit42.fakes.FakeUiDeploymentService;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.kinotic.summit42.internal.api.repositories.BuilderSessionRepository;
import org.kinotic.summit42.internal.api.repositories.ConversationRepository;
import org.kinotic.system.api.model.llm.LlmConfiguration;
import org.kinotic.system.api.model.llm.LlmProviderType;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

/**
 * Covers the app builder's flows against fakes of the Kinotic services it calls, and a fake of Anthropic's API the
 * platform's LLM configuration points at.
 */
public class AppBuilderFlowTests extends AppBuilderServiceTestBase {

    private static final String COMMIT = "c0ffee";

    @Autowired
    private FakeApplicationService applicationService;
    @Autowired
    private FakeProjectService projectService;
    @Autowired
    private FakeUiDeploymentService uiDeploymentService;
    @Autowired
    private FakeLlmConfigurationService llmConfigurationService;
    @Autowired
    private FakeAnthropic anthropic;
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
        BuilderApp app = await(callAs(participant, service -> service.createApp("  Todo List ", "  ")));

        Application application = applicationService.applicationsOf(organizationId()).values().iterator().next();
        Assertions.assertEquals("Todo List", application.getName());
        Assertions.assertNull(application.getDescription());
        Assertions.assertEquals(organizationId(), application.getOrganizationId());
        Assertions.assertEquals(TenantManagement.PLATFORM, application.getTenantManagement());
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
    }

    @Test
    public void testCreateAppDeletesTheApplicationWhenItsProjectCannotBeCreated() {
        projectService.failCreations(organizationId());

        ExecutionException e = Assertions.assertThrows(ExecutionException.class,
                                                       () -> await(callAs(participant, service -> service.createApp("Todo", null))));

        Assertions.assertEquals("The repository could not be created", e.getCause().getMessage());
        Assertions.assertTrue(applicationService.applicationsOf(organizationId()).isEmpty());
    }

    @Test
    public void testCreateAppRendersARepositoryWhoseInitializationFailedAgain() throws Exception {
        projectService.createWith(organizationId(), RepositoryConnectionStatus.INITIALIZATION_FAILED);

        BuilderApp app = await(callAs(participant, service -> service.createApp("Todo", null)));

        Assertions.assertEquals("Todo", app.getName());
        Assertions.assertEquals(RepositoryConnectionStatus.CONNECTED,
                                projectService.projectsOf(organizationId()).getFirst().getRepoConnectionStatus());
    }

    @Test
    public void testCreateAppFailsWhenTheRepositoryCannotBeRenderedAgain() {
        projectService.createWith(organizationId(), RepositoryConnectionStatus.INITIALIZATION_FAILED);
        projectService.failRetries(organizationId());

        ExecutionException e = Assertions.assertThrows(ExecutionException.class,
                                                       () -> await(callAs(participant, service -> service.createApp("Todo", null))));

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

    private String organizationId() {
        return participant.getOrganizationId();
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
