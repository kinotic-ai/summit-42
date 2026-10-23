package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.model.TenantManagement;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.ProjectType;
import org.kinotic.management.api.model.RepositoryConnectionStatus;
import org.kinotic.management.api.model.deployment.DeploymentState;
import org.kinotic.management.api.model.deployment.UiDeployment;
import org.kinotic.management.api.services.ApplicationService;
import org.kinotic.management.api.services.ProjectService;
import org.kinotic.management.api.services.deployment.UiDeploymentService;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuildEventType;
import org.kinotic.summit42.api.model.BuilderApp;
import org.kinotic.summit42.api.model.ChatMessage;
import org.kinotic.summit42.api.model.ChatRole;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.api.services.AppBuilderService;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.kinotic.summit42.internal.api.repositories.BuilderSessionRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAppBuilderService implements AppBuilderService {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/app-builder.md";
    private static final String TURN_RUNNING = "Claude is still working on your last message";
    private static final String TURN_FAILED = "Something went wrong. Please try again.";
    private static final String REPOSITORY_UNUSABLE = "Summit 42 can no longer reach this app's GitHub repository."
                                                      + " On GitHub, give the Kinotic app access to the repository again.";
    // the most recent messages of the conversation a turn sends Claude
    private static final int MAX_HISTORY_MESSAGES = 40;
    // the size of the pages the organization's applications and projects are read in
    private static final int PAGE_SIZE = 100;
    // a tool's progress event and the answer's completion can be emitted at the same moment; the sink serializes them
    // by retrying briefly
    private static final Sinks.EmitFailureHandler RETRY_CONCURRENT_EMIT = Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));

    private final Vertx vertx;
    private final SecurityContext securityContext;
    private final ApplicationService applicationService;
    private final ProjectService projectService;
    private final UiDeploymentService uiDeploymentService;
    private final BuilderSessionRepository sessionRepository;
    private final ChatClientFactory chatClientFactory;
    private final AppBuilderTools tools;
    private final KinoticSkills kinoticSkills;
    private final SystemMessage systemMessage = new SystemMessage(loadSystemPrompt());

    @Override
    public Future<List<BuilderApp>> findApps() {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        Future<List<Application>> applications = findEvery(participant, applicationService::findAll);
        Future<List<Project>> projects = findEvery(participant, projectService::findAll);
        return Future.all(applications, projects)
                     .compose(both -> {
                         // an application without a project is not one the builder can work on
                         Set<String> built = projects.result().stream()
                                                     .map(Project::getApplicationId)
                                                     .collect(Collectors.toSet());
                         List<Future<BuilderApp>> apps =
                                 applications.result().stream()
                                             .filter(application -> built.contains(application.getId()))
                                             .sorted(Comparator.comparing(Application::getName, String.CASE_INSENSITIVE_ORDER))
                                             .map(application -> toBuilderApp(participant, application))
                                             .toList();
                         return Future.all(apps).map(all -> apps.stream().map(Future::result).toList());
                     });
    }

    @Override
    public Future<BuilderApp> createApp(String name, String description) {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        Validate.notBlank(name, "Name the app");
        // the platform gives each of the app's users a tenant, so an entity can keep its rows private to each user
        Application application = new Application(name.strip(), StringUtils.trimToNull(description))
                .setOrganizationId(participant.getOrganizationId())
                .setTenantManagement(TenantManagement.PLATFORM);
        return callAs(participant, () -> applicationService.createSync(application))
                // an app deleted earlier under the same name had the same id, and its conversation is not this app's;
                // one that cannot be deleted is logged rather than failing an app that was created
                .compose(created -> sessionRepository.deleteById(new ApplicationKey(participant.getOrganizationId(), created.getId()))
                                                     .onFailure(e -> log.error("The earlier conversation of app {} could not be deleted",
                                                                               created.getId(), e))
                                                     .otherwiseEmpty()
                                                     .map(created))
                .compose(created -> createProject(participant, created)
                        .compose(project -> buildable(participant, project))
                        .map(project -> new BuilderApp(created.getId(), created.getName(), created.getDescription(), null)));
    }

    @Override
    public Flux<BuildEvent> chat(String applicationId, String message) {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        ApplicationKey applicationKey = new ApplicationKey(participant.getOrganizationId(), applicationId);
        Flux<BuildEvent> ret;
        if (message == null || message.isBlank()) {
            ret = Flux.just(BuildEvent.error("The message is empty"));
        } else {
            // the turn takes the session when the stream is subscribed, so a stream never subscribed holds nothing
            ret = Mono.fromCompletionStage(() -> findBuildableProject(participant, applicationId)
                              .compose(project -> sessionRepository.findById(applicationKey)
                                                                   .compose(session -> kinoticSkills.current()
                                                                           .map(skills -> Tuples.of(project, session, skills))))
                              .toCompletionStage())
                      .flatMapMany(found -> startTurn(participant, found.getT2(), found.getT1(), found.getT3(), message))
                      .onErrorResume(e -> failure(applicationKey, e));
        }
        return ret;
    }

    @Override
    public Flux<BuildEvent> followRunningTurn(String applicationId) {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        ApplicationKey applicationKey = new ApplicationKey(participant.getOrganizationId(), applicationId);
        return Mono.fromCompletionStage(() -> findProject(participant, applicationId)
                           .compose(project -> sessionRepository.findById(applicationKey))
                           .toCompletionStage())
                   .flatMapMany(session -> {
                       Flux<BuildEvent> events;
                       synchronized (session) {
                           events = session.getTurnEvents();
                       }
                       return events != null ? events : Flux.<BuildEvent>empty();
                   })
                   .onErrorResume(e -> failure(applicationKey, e));
    }

    @Override
    public Future<Conversation> findConversation(String applicationId) {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        return findProject(participant, applicationId)
                .compose(project -> sessionRepository.findById(new ApplicationKey(participant.getOrganizationId(), applicationId)))
                .map(session -> {
                    synchronized (session) {
                        return new Conversation().setMessages(new ArrayList<>(session.getConversation().getMessages()));
                    }
                });
    }

    @Override
    public Future<Void> resetConversation(String applicationId) {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        return findProject(participant, applicationId)
                .compose(project -> sessionRepository.findById(new ApplicationKey(participant.getOrganizationId(), applicationId)))
                .compose(session -> {
                    Future<Void> ret;
                    synchronized (session) {
                        if (session.isTurnRunning()) {
                            ret = Future.failedFuture(new IllegalStateException(TURN_RUNNING + "; reset the conversation once it is done"));
                        } else {
                            session.getConversation().getMessages().clear();
                            ret = sessionRepository.save(session);
                        }
                    }
                    return ret;
                });
    }

    // a message sent while a turn takes messages steers it, one sent as its stream finishes waits for it to end, and
    // any other starts a turn unless one is running
    private Flux<BuildEvent> startTurn(OrganizationParticipant participant, BuilderSession session, Project project,
                                       SkillSet skills, String message) {
        Flux<BuildEvent> ret = null;
        synchronized (session) {
            if (session.getSteeringMessages() != null) {
                session.getSteeringMessages().add(message);
                ret = Flux.empty();
            } else if (session.getTurnEvents() != null) {
                ret = session.getTurnEvents()
                             .ignoreElements()
                             .thenMany(Flux.defer(() -> startTurn(participant, session, project, skills, message)));
            } else if (session.isTurnRunning()) {
                ret = Flux.just(BuildEvent.error(TURN_RUNNING));
            } else {
                session.setTurnRunning(true);
                session.setSteeringMessages(new ArrayList<>());
                session.getConversation().getMessages().add(new ChatMessage(ChatRole.USER, message));
            }
        }
        if (ret == null) {
            save(session);
            ret = runTurn(participant, session, project, skills);
        }
        return ret;
    }

    private Flux<BuildEvent> runTurn(OrganizationParticipant participant, BuilderSession session, Project project,
                                     SkillSet skills) {
        Sinks.Many<BuildEvent> progress = Sinks.many().unicast().onBackpressureBuffer();
        // the turn's reply since the last message it read, and the messages it has finished, which join the
        // conversation when it ends; both are guarded by the session
        StringBuilder reply = new StringBuilder();
        List<ChatMessage> finished = new ArrayList<>();
        AtomicBoolean stepSinceText = new AtomicBoolean();

        // each message the user sent to steer the turn is reported where Claude reads it
        Consumer<List<String>> report =
                steering -> steering.forEach(message -> progress.emitNext(BuildEvent.userMessage(message), RETRY_CONCURRENT_EMIT));
        BuilderTurn turn = new BuilderTurn(participant, session, project.getId(), skills,
                                           event -> progress.emitNext(event, RETRY_CONCURRENT_EMIT),
                                           () -> {
                                               List<String> steering = takeSteering(session, reply, finished);
                                               report.accept(steering);
                                               return steering;
                                           });

        Flux<BuildEvent> answer =
                Flux.defer(() -> {
                        List<Message> prompt;
                        synchronized (session) {
                            List<ChatMessage> messages = new ArrayList<>(session.getConversation().getMessages());
                            messages.addAll(finished);
                            prompt = promptFor(project, skills, messages);
                        }
                        return Mono.fromCompletionStage(() -> chatClientFactory.chatClient())
                                   .flatMapMany(client -> client.prompt()
                                                                .messages(prompt)
                                                                .tools(tools)
                                                                .toolContext(Map.of(AppBuilderTools.TURN, turn))
                                                                .stream()
                                                                .content());
                    })
                    .map(BuildEvent::text)
                    // a message sent after Claude's last step gets an answer of its own, to the messages so far ending
                    // with it; the turn stops taking messages once an answer ends with none sent
                    .repeat(() -> {
                        List<String> steering;
                        synchronized (session) {
                            steering = takeSteering(session, reply, finished);
                            if (steering.isEmpty()) {
                                session.setSteeringMessages(null);
                            }
                        }
                        report.accept(steering);
                        return !steering.isEmpty();
                    })
                    .onErrorResume(e -> failure(session.getApplicationKey(), e))
                    // the progress stream ends with the answer, so the merged turn completes with it
                    .doFinally(signal -> progress.emitComplete(RETRY_CONCURRENT_EMIT));

        // runs before the stream's completion reaches a subscriber, so a client that reads the conversation or sends
        // its next message on completion finds the reply recorded and the turn over
        Runnable endTurn = () -> {
            synchronized (session) {
                finish(reply, finished);
                session.getConversation().getMessages().addAll(finished);
                save(session);
                session.setTurnEvents(null);
                session.setSteeringMessages(null);
            }
            turn.release();
        };
        // connected here rather than by a subscriber, so the turn runs to its end when the chat that started it goes
        // away, and a chat that comes back follows it from its first event through followRunningTurn
        ConnectableFlux<BuildEvent> events = Flux.merge(answer, progress.asFlux())
                   .doOnNext(event -> {
                       // text resumed after a tool call's step starts a new paragraph, as the chat shows it apart
                       if (event.getType() != BuildEventType.TEXT) {
                           stepSinceText.set(true);
                       } else {
                           synchronized (session) {
                               if (stepSinceText.getAndSet(false) && !reply.isEmpty()) {
                                   reply.append("\n\n");
                               }
                               reply.append(event.getText());
                           }
                       }
                   })
                   .doOnTerminate(endTurn)
                   .replay();
        synchronized (session) {
            session.setTurnEvents(events);
        }
        events.connect();
        return events;
    }

    // takes the messages sent to steer the turn, after the reply so far, which is finished as a message of its own
    private static List<String> takeSteering(BuilderSession session, StringBuilder reply, List<ChatMessage> finished) {
        List<String> ret;
        synchronized (session) {
            ret = List.copyOf(session.getSteeringMessages());
            session.getSteeringMessages().clear();
            if (!ret.isEmpty()) {
                finish(reply, finished);
                ret.forEach(message -> finished.add(new ChatMessage(ChatRole.USER, message)));
            }
        }
        return ret;
    }

    // the reply so far joins the finished messages as the assistant's, and the next reply starts empty
    private static void finish(StringBuilder reply, List<ChatMessage> finished) {
        if (!reply.isEmpty()) {
            finished.add(new ChatMessage(ChatRole.ASSISTANT, reply.toString()));
            reply.setLength(0);
        }
    }

    // a conversation that could not be saved stays in this server's memory, so the chat carries on
    private void save(BuilderSession session) {
        sessionRepository.save(session)
                         .onFailure(e -> log.error("The conversation of app {} could not be saved", session.getApplicationKey(), e));
    }


    // an application whose project could not be created is deleted, so its name can be used again
    private Future<Project> createProject(OrganizationParticipant participant, Application application) {
        Project project = new Project();
        project.setName(application.getName());
        project.setDescription(application.getDescription());
        project.setOrganizationId(participant.getOrganizationId());
        project.setApplicationId(application.getId());
        project.setSourceOfTruth(ProjectType.TYPESCRIPT);
        project.setRepoPrivate(true);
        return callAs(participant, () -> projectService.createSync(project))
                .recover(failure -> callAs(participant, () -> applicationService.deleteById(application.getId()))
                        .transform(deleted -> {
                            if (deleted.failed()) {
                                log.error("Application {} of organization {} is left without a project",
                                          application.getId(), participant.getOrganizationId(), deleted.cause());
                            }
                            return Future.failedFuture(failure);
                        }));
    }

    // the app's project once it can be built in; fails with BuildUnavailableException when the organization has no
    // such app or its repository cannot be used
    private Future<Project> findBuildableProject(OrganizationParticipant participant, String applicationId) {
        return findProject(participant, applicationId).compose(project -> buildable(participant, project));
    }

    // the app's project, failing for an id that names no app of the organization
    private Future<Project> findProject(OrganizationParticipant participant, String applicationId) {
        return callAs(participant, () -> projectService.findAllForApplication(applicationId, Pageable.ofSize(1)))
                .map(page -> {
                    if (page.getContent().isEmpty()) {
                        throw new BuildUnavailableException("This app no longer exists");
                    }
                    return page.getContent().getFirst();
                });
    }

    // a repository whose template rendering failed is rendered again once; a second failure fails the future
    private Future<Project> buildable(OrganizationParticipant participant, Project project) {
        Future<Project> initialized;
        if (project.getRepoConnectionStatus() == RepositoryConnectionStatus.INITIALIZATION_FAILED) {
            initialized = callAs(participant, () -> projectService.retryRepoInitialization(project.getId()));
        } else {
            initialized = Future.succeededFuture(project);
        }
        return initialized.map(ready -> {
            if (ready.getRepoConnectionStatus() != RepositoryConnectionStatus.CONNECTED) {
                throw new BuildUnavailableException(REPOSITORY_UNUSABLE);
            }
            return ready;
        });
    }

    // the URL of the app is that of the first of its UIs serving a commit
    private Future<BuilderApp> toBuilderApp(OrganizationParticipant participant, Application application) {
        return callAs(participant, () -> uiDeploymentService.findAllForApplication(application.getId()))
                .map(uis -> new BuilderApp(application.getId(),
                                           application.getName(),
                                           application.getDescription(),
                                           uis.stream()
                                              .filter(ui -> DeploymentState.commitOf(ui.getState().getObserved()) != null)
                                              .map(UiDeployment::getUrl)
                                              .findFirst()
                                              .orElse(null)));
    }

    private <T> Future<List<T>> findEvery(OrganizationParticipant participant, Function<Pageable, Future<Page<T>>> find) {
        return findEvery(participant, find, 0, new ArrayList<>());
    }

    // reads the pages from pageNumber on into found, until a page comes back short
    private <T> Future<List<T>> findEvery(OrganizationParticipant participant,
                                          Function<Pageable, Future<Page<T>>> find,
                                          int pageNumber,
                                          List<T> found) {
        return callAs(participant, () -> find.apply(Pageable.create(pageNumber, PAGE_SIZE, null)))
                .compose(page -> {
                    found.addAll(page.getContent());
                    Future<List<T>> ret;
                    if (page.getContent().size() < PAGE_SIZE) {
                        ret = Future.succeededFuture(found);
                    } else {
                        ret = findEvery(participant, find, pageNumber + 1, found);
                    }
                    return ret;
                });
    }

    // the call is made as the participant, and completes on the caller's Vert.x context
    private <T> Future<T> callAs(OrganizationParticipant participant, Supplier<Future<T>> call) {
        return Future.fromCompletionStage(securityContext.callAs(participant, () -> call.get().toCompletionStage()),
                                          vertx.getOrCreateContext());
    }

    private static Flux<BuildEvent> failure(ApplicationKey applicationKey, Throwable e) {
        log.error("Chat turn of app {} failed", applicationKey, e);
        String text = e instanceof BuildUnavailableException ? e.getMessage() : TURN_FAILED;
        return Flux.just(BuildEvent.error(text));
    }

    // the system prompt, the skills Claude can read, what the app is, and the most recent of the messages, which end
    // with the one to answer; tool calls of earlier answers are not kept, so Claude reads the repository again when it
    // needs to
    private List<Message> promptFor(Project project, SkillSet skills, List<ChatMessage> messages) {
        int from = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
        // a window that cuts an exchange in two drops the reply whose message it cut off
        if (from > 0 && messages.get(from).getRole() == ChatRole.ASSISTANT) {
            from++;
        }
        List<Message> ret = new ArrayList<>();
        ret.add(systemMessage);
        ret.add(new SystemMessage("The Kinotic skills readSkill reads:\n" + skills.catalog()));
        // the project carries the name and description the user gave the app
        ret.add(new SystemMessage("The user named this app '" + project.getName() + "'"
                                  + (project.getDescription() != null ? " and described it as: " + project.getDescription() : "")
                                  + "."));
        for (ChatMessage chatMessage : messages.subList(from, messages.size())) {
            ret.add(chatMessage.getRole() == ChatRole.USER
                            ? new UserMessage(chatMessage.getText())
                            : new AssistantMessage(chatMessage.getText()));
        }
        return ret;
    }

    private static String loadSystemPrompt() {
        try {
            return new ClassPathResource(SYSTEM_PROMPT_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + SYSTEM_PROMPT_RESOURCE, e);
        }
    }
}
