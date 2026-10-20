package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.model.ReconcileState;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.ProjectRepoToken;
import org.kinotic.management.api.model.ProjectType;
import org.kinotic.management.api.model.RepositoryConnectionStatus;
import org.kinotic.management.api.model.deployment.DeploymentState;
import org.kinotic.management.api.model.deployment.DeploymentStatusType;
import org.kinotic.management.api.model.deployment.ProjectDeployment;
import org.kinotic.management.api.model.deployment.UiDeployment;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.internal.api.proxies.ApplicationServiceProxy;
import org.kinotic.summit42.internal.api.proxies.ProjectRepoCredentialServiceProxy;
import org.kinotic.summit42.internal.api.proxies.ProjectServiceProxy;
import org.kinotic.summit42.internal.api.proxies.UiDeploymentServiceProxy;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The tools Claude builds an organization's app with. Each acts for the turn's signed-in user, so Kinotic authorizes
 * every call against their organization, and reports what it does as {@link BuildEvent#progress(String)} events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppBuilderTools {

    /**
     * The {@link ToolContext} key of the {@link BuilderTurn} a tool call belongs to.
     */
    public static final String TURN = "turn";

    // the id of the application every organization's app is built in, so the app is found again after a restart
    private static final String APPLICATION_ID = "summit-42";
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEPLOYMENT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEPLOYMENT_POLL_INTERVAL = Duration.ofSeconds(5);
    // a repository token is renewed this long before it expires, so a commit never starts with one about to stop working
    private static final Duration TOKEN_RENEWAL_MARGIN = Duration.ofMinutes(5);
    private static final String DOCS_HOST = "raw.githubusercontent.com";
    private static final String DOCS_PATH = "/kinotic-ai/kinotic/develop/website/content/01.apps/";
    private static final List<String> DOC_PAGES = List.of(
            "01.introduction.md",
            "02.quick-start.md",
            "03.application-structure/01.overview.md",
            "03.application-structure/02.applications-and-projects.md",
            "03.application-structure/03.artifact-types.md",
            "04.services/01.overview.md",
            "04.services/02.publishing-services.md",
            "04.services/03.service-proxies.md",
            "04.services/04.streaming.md",
            "05.persistence/01.overview.md",
            "05.persistence/02.defining-entities.md",
            "05.persistence/03.entity-decorators.md",
            "05.persistence/04.crud-operations.md",
            "05.persistence/05.named-queries.md",
            "05.persistence/06.multi-tenancy.md",
            "05.persistence/07.migrations.md",
            "06.security/01.authentication.md",
            "07.deployment/01.workflow.md",
            "07.deployment/02.environments.md",
            "07.deployment/03.push-to-deploy.md",
            "09.reference/01.decorators.md",
            "09.reference/02.migration-sql-grammar.md");

    private final Vertx vertx;
    private final SecurityContext securityContext;
    private final ApplicationServiceProxy applicationService;
    private final ProjectServiceProxy projectService;
    private final UiDeploymentServiceProxy uiDeploymentService;
    private final ProjectRepoCredentialServiceProxy repoCredentialService;
    private final GitHubRepoClient gitHubRepoClient;
    private final WebClient webClient;

    @Tool(description = """
            Creates the user's app: a Kinotic application with one project, whose GitHub repository is generated from \
            Kinotic's app template. Call it once, before any other tool, when the user asks for an app and none exists; \
            when the app already exists it returns the existing one. Returns the repository's files.""")
    public String createApp(@ToolParam(description = "A short name for the app, such as 'Todo'") String name,
                            @ToolParam(description = "One sentence on what the app is for") String description,
                            ToolContext toolContext) {
        return act(toolContext, turn -> {
            BuilderSession session = turn.getSession();
            String ret;
            if (session.getProjectId() != null) {
                ret = "The app already exists.\n" + describeFiles(turn);
            } else {
                Validate.notBlank(name, "name is required");
                turn.getEvents().accept(BuildEvent.progress("Creating the app"));
                Application application = findOrCreateApplication(turn, name, description);
                Project project = initialized(turn, findOrCreateProject(turn, application, name, description));
                if (project.getRepoConnectionStatus() != RepositoryConnectionStatus.CONNECTED) {
                    ret = "The app's GitHub repository cannot be used: Kinotic reports it "
                          + Objects.toString(project.getRepoConnectionStatus(), "missing")
                          + ". Ask the user to check the GitHub connection of their Kinotic organization.";
                } else {
                    session.setProjectId(project.getId());
                    log.info("Organization {} builds in project {}", session.getOrganizationId(), project.getId());
                    ret = "The app is '" + application.getName() + "'.\n" + describeFiles(turn);
                }
            }
            return ret;
        });
    }

    @Tool(description = "Lists the path of every file in the app's repository on its default branch.")
    public String listFiles(ToolContext toolContext) {
        return act(toolContext, this::describeFiles);
    }

    @Tool(description = "Reads files of the app's repository, returning each file's content under a header naming it.")
    public String readFiles(@ToolParam(description = "Repository paths of the files to read") List<String> paths,
                            ToolContext toolContext) {
        return act(toolContext, turn -> {
            ProjectRepoToken token = repoToken(turn);
            StringBuilder ret = new StringBuilder();
            for (String path : paths) {
                String content = await(gitHubRepoClient.readFile(token, path));
                ret.append("=== ").append(path).append(" ===\n")
                   .append(content != null ? content : "(no such file)")
                   .append("\n");
            }
            return ret.toString();
        });
    }

    @Tool(description = """
            Writes files to the app's repository as one commit on its default branch, which deploys the app. Each file \
            carries its full new content; a file without content is deleted. Write every change of a step in one call, \
            then call waitForDeployment.""")
    public String writeFiles(@ToolParam(description = "The commit message") String message,
                             @ToolParam(description = "The files to write or delete") List<RepoFile> files,
                             ToolContext toolContext) {
        return act(toolContext, turn -> {
            Validate.notEmpty(files, "files are required");
            ProjectRepoToken token = repoToken(turn);
            turn.getEvents().accept(BuildEvent.progress("Committing " + files.size() + (files.size() == 1 ? " file" : " files")));
            String commitSha = await(gitHubRepoClient.commit(token, message, files));
            turn.getSession().setLastCommitSha(commitSha);
            return "Committed " + commitSha + "; its deployment has started.";
        });
    }

    @Tool(description = """
            Waits until the deployment of the last commit writeFiles made has finished and its UIs serve it, and returns \
            its outcome: the URLs the app's UIs serve at, or why it failed. A failed deployment names the error to fix.""")
    public String waitForDeployment(ToolContext toolContext) {
        return act(toolContext, turn -> {
            String commitSha = turn.getSession().getLastCommitSha();
            Validate.validState(commitSha != null, "Nothing has been committed yet");
            long deadline = System.nanoTime() + DEPLOYMENT_TIMEOUT.toNanos();
            ProjectDeployment deployment = awaitDeployment(turn, commitSha, deadline);
            String ret;
            if (deployment == null) {
                ret = "The deployment of commit " + commitSha + " has not finished after "
                      + DEPLOYMENT_TIMEOUT.toMinutes() + " minutes";
            } else if (deployment.getState().getObserved().phase() == DeploymentStatusType.FAILED) {
                ret = "The deployment failed: " + deployment.getFailureMessage();
            } else {
                ret = awaitUis(turn, commitSha, deadline);
            }
            return ret;
        });
    }

    @Tool(description = "Reads a page of Kinotic's documentation for building apps. Pages: " + "01.introduction.md, "
                        + "02.quick-start.md, 03.application-structure/*, 04.services/*, 05.persistence/*, "
                        + "06.security/01.authentication.md, 07.deployment/*, 09.reference/01.decorators.md, "
                        + "09.reference/02.migration-sql-grammar.md; call it with an unknown page to list every page.")
    public String readKinoticDocs(@ToolParam(description = "The page's path, such as 05.persistence/02.defining-entities.md")
                                  String page) {
        String ret;
        if (!DOC_PAGES.contains(page)) {
            ret = "Unknown page. The pages are:\n" + String.join("\n", DOC_PAGES);
        } else {
            ret = await(webClient.get(443, DOCS_HOST, DOCS_PATH + page)
                                 .ssl(true)
                                 .send()
                                 .map(resp -> resp.statusCode() == 200
                                         ? resp.bodyAsString()
                                         : "The page could not be read: HTTP " + resp.statusCode()));
        }
        return ret;
    }

    private String describeFiles(BuilderTurn turn) {
        ProjectRepoToken token = repoToken(turn);
        RepoTree tree = await(gitHubRepoClient.listFiles(token));
        String ret = "Files on " + token.getDefaultBranch() + ":\n" + String.join("\n", tree.paths());
        if (tree.truncated()) {
            ret += "\nThe list is incomplete: GitHub lists only part of a repository this large, so a file missing from it"
                   + " may still exist.";
        }
        return ret;
    }

    private Application findOrCreateApplication(BuilderTurn turn, String name, String description) {
        Application found = callAs(turn, () -> applicationService.findById(APPLICATION_ID).toCompletionStage());
        Application ret;
        if (found != null) {
            ret = found;
        } else {
            Application application = new Application(name, description)
                    .setId(APPLICATION_ID)
                    .setOrganizationId(turn.getParticipant().getOrganizationId());
            ret = callAs(turn, () -> applicationService.createSync(application).toCompletionStage());
        }
        return ret;
    }

    private Project findOrCreateProject(BuilderTurn turn, Application application, String name, String description) {
        Page<Project> page = callAs(turn, () -> projectService.findAllForApplication(application.getId(), Pageable.ofSize(1))
                                                              .toCompletionStage());
        Project ret;
        if (page.getContent().isEmpty()) {
            Project project = new Project();
            project.setName(name);
            project.setDescription(description);
            project.setOrganizationId(turn.getParticipant().getOrganizationId());
            project.setApplicationId(application.getId());
            project.setSourceOfTruth(ProjectType.TYPESCRIPT);
            project.setRepoPrivate(true);
            ret = callAs(turn, () -> projectService.createSync(project).toCompletionStage());
        } else {
            ret = page.getContent().getFirst();
        }
        return ret;
    }

    // a repository whose template rendering failed is rendered again once; a second failure fails the tool call
    private Project initialized(BuilderTurn turn, Project project) {
        Project ret;
        if (project.getRepoConnectionStatus() == RepositoryConnectionStatus.INITIALIZATION_FAILED) {
            turn.getEvents().accept(BuildEvent.progress("Preparing the app's repository again"));
            ret = callAs(turn, () -> projectService.retryRepoInitialization(project.getId()).toCompletionStage());
        } else {
            ret = project;
        }
        return ret;
    }

    // the project's deployment once it has finished deploying the commit, or null when it has not by the deadline
    private ProjectDeployment awaitDeployment(BuilderTurn turn, String commitSha, long deadline) {
        String projectId = turn.getSession().getProjectId();
        DeploymentStatusType reported = null;
        ProjectDeployment ret = null;
        while (ret == null && System.nanoTime() < deadline) {
            ProjectDeployment deployment = callAs(turn, () -> projectService.findDeployment(projectId).toCompletionStage());
            ReconcileState<DeploymentState> state = deployment != null ? deployment.getState() : null;
            boolean intended = state != null && state.getDesired() != null && commitSha.equals(state.getDesired().commitSha());
            if (intended && state.getObserved() != null && state.getObserved().phase() != reported) {
                reported = state.getObserved().phase();
                turn.getEvents().accept(BuildEvent.progress("Deploying: " + reported.name().toLowerCase()));
            }
            // the observation answers this commit's push once the deployment has seen that intent and stopped
            // deploying; a FAILED observation names the commit still served, not the one that failed
            if (intended && state.getObservedGeneration() >= state.getGeneration() && state.getObserved() != null
                && state.getObserved().phase() != DeploymentStatusType.DEPLOYING) {
                ret = deployment;
            } else {
                await(vertx.timer(DEPLOYMENT_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS));
            }
        }
        return ret;
    }

    // the outcome of the commit's UIs once each serves it, or as they are at the deadline
    private String awaitUis(BuilderTurn turn, String commitSha, long deadline) {
        List<UiDeployment> uis = commitUis(turn, commitSha);
        if (!allServe(uis, commitSha)) {
            turn.getEvents().accept(BuildEvent.progress("Publishing the app's pages"));
        }
        while (!allServe(uis, commitSha) && System.nanoTime() < deadline) {
            await(vertx.timer(DEPLOYMENT_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS));
            uis = commitUis(turn, commitSha);
        }
        List<String> lines = new ArrayList<>();
        String appUrl = null;
        for (UiDeployment ui : uis) {
            boolean serving = serves(ui, commitSha);
            DeploymentState observed = ui.getState().getObserved();
            lines.add("UI " + ui.getName() + ": " + (serving
                    ? "serving at " + ui.getUrl()
                    : "not serving this commit yet (" + Objects.requireNonNullElse(ui.getObservation(), describe(observed)) + ")"));
            if (serving && appUrl == null) {
                appUrl = ui.getUrl();
            }
        }
        if (appUrl != null) {
            turn.getSession().getConversation().setAppUrl(appUrl);
            turn.getEvents().accept(BuildEvent.appReady(appUrl));
        }
        return "The deployment is running commit " + commitSha + ".\n"
               + (lines.isEmpty() ? "The commit has no UI." : String.join("\n", lines));
    }

    // the UI deployments the commit publishes; one the commit dropped is meant to be ORPHANED instead
    private List<UiDeployment> commitUis(BuilderTurn turn, String commitSha) {
        String projectId = turn.getSession().getProjectId();
        DeploymentState intent = new DeploymentState(DeploymentStatusType.READY, commitSha);
        return callAs(turn, () -> uiDeploymentService.findAllForProject(projectId).toCompletionStage())
                .stream()
                .filter(ui -> intent.equals(ui.getState().getDesired()))
                .toList();
    }

    private static boolean allServe(List<UiDeployment> uis, String commitSha) {
        return uis.stream().allMatch(ui -> serves(ui, commitSha));
    }

    private static boolean serves(UiDeployment ui, String commitSha) {
        ReconcileState<DeploymentState> state = ui.getState();
        return state.getObservedGeneration() >= state.getGeneration()
               && new DeploymentState(DeploymentStatusType.READY, commitSha).equals(state.getObserved());
    }

    private static String describe(DeploymentState state) {
        return state == null ? "no state yet" : state.phase().name().toLowerCase();
    }

    private ProjectRepoToken repoToken(BuilderTurn turn) {
        BuilderSession session = turn.getSession();
        String projectId = session.getProjectId();
        Validate.validState(projectId != null, "The app does not exist yet; call createApp first");
        ProjectRepoToken ret = session.getRepoToken();
        if (ret == null || Instant.now().plus(TOKEN_RENEWAL_MARGIN).isAfter(ret.getExpiresAt())) {
            ret = callAs(turn, () -> repoCredentialService.issueWriteToken(projectId).toCompletionStage());
            session.setRepoToken(ret);
        }
        return ret;
    }

    // holds the turn while the tool runs, so a cancelled turn keeps its session until the running tool returns
    private static String act(ToolContext toolContext, Function<BuilderTurn, String> work) {
        BuilderTurn turn = (BuilderTurn) toolContext.getContext().get(TURN);
        Validate.validState(turn.hold(), "The turn has ended");
        try {
            return work.apply(turn);
        } finally {
            turn.release();
        }
    }

    private <T> T callAs(BuilderTurn turn, Supplier<? extends CompletionStage<T>> call) {
        try {
            return securityContext.callAs(turn.getParticipant(), call).get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }
    }

    // tools run on Spring AI's boundedElastic workers, so blocking on a future is allowed here
    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
    }
}
