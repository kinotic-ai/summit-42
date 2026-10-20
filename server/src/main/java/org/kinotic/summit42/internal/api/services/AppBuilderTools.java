package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.model.TenantManagement;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.ProjectRepoToken;
import org.kinotic.management.api.model.ProjectType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The tools Claude builds an organization's app with. Each acts for the turn's signed-in user, so Kinotic authorizes
 * every call against their organization, and reports what it does as {@link BuildEvent#progress(String)} events.
 */
@Slf4j
@Component
public class AppBuilderTools {

    /**
     * The {@link ToolContext} key of the {@link BuilderTurn} a tool call belongs to.
     */
    public static final String TURN = "turn";

    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEPLOYMENT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEPLOYMENT_POLL_INTERVAL = Duration.ofSeconds(5);
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
    private final WebClient docsClient;

    public AppBuilderTools(Vertx vertx,
                           SecurityContext securityContext,
                           ApplicationServiceProxy applicationService,
                           ProjectServiceProxy projectService,
                           UiDeploymentServiceProxy uiDeploymentService,
                           ProjectRepoCredentialServiceProxy repoCredentialService,
                           GitHubRepoClient gitHubRepoClient) {
        this.vertx = vertx;
        this.securityContext = securityContext;
        this.applicationService = applicationService;
        this.projectService = projectService;
        this.uiDeploymentService = uiDeploymentService;
        this.repoCredentialService = repoCredentialService;
        this.gitHubRepoClient = gitHubRepoClient;
        this.docsClient = WebClient.create(vertx);
    }

    @Tool(description = """
            Creates the user's app: a Kinotic application with one project, whose GitHub repository is generated from \
            Kinotic's app template. Call it once, before any other tool, when the user asks for an app and none exists; \
            when the app already exists it returns the existing one. Returns the repository's files.""")
    public String createApp(@ToolParam(description = "A short name for the app, such as 'Todo'") String name,
                            @ToolParam(description = "One sentence on what the app is for") String description,
                            ToolContext toolContext) {
        BuilderTurn turn = turn(toolContext);
        BuilderSession session = turn.session();
        String ret;
        if (session.getProjectId() != null) {
            ret = "The app already exists.\n" + listFiles(toolContext);
        } else {
            Validate.notBlank(name, "name is required");
            turn.events().accept(BuildEvent.progress("Creating the app"));
            Application application = callAs(turn, () -> applicationService
                    .createApplicationIfNotExist(name, description, TenantManagement.TENANT_ADMINISTRATORS)
                    .toCompletionStage());
            Project project = callAs(turn, () -> projectService
                    .findAllForApplication(application.getId(), Pageable.ofSize(1))
                    .compose(page -> page.getContent().isEmpty()
                            ? projectService.createSync(newProject(turn, application, name, description))
                            : Future.succeededFuture(page.getContent().getFirst()))
                    .toCompletionStage());
            session.setProjectId(project.getId());
            log.info("Organization {} builds in project {}", session.getOrganizationId(), project.getId());
            ret = "Created the app '" + application.getName() + "'.\n" + listFiles(toolContext);
        }
        return ret;
    }

    @Tool(description = "Lists the path of every file in the app's repository on its default branch.")
    public String listFiles(ToolContext toolContext) {
        BuilderTurn turn = turn(toolContext);
        ProjectRepoToken token = repoToken(turn);
        List<String> files = await(gitHubRepoClient.listFiles(token));
        return "Files on " + token.getDefaultBranch() + ":\n" + String.join("\n", files);
    }

    @Tool(description = "Reads files of the app's repository, returning each file's content under a header naming it.")
    public String readFiles(@ToolParam(description = "Repository paths of the files to read") List<String> paths,
                            ToolContext toolContext) {
        BuilderTurn turn = turn(toolContext);
        ProjectRepoToken token = repoToken(turn);
        StringBuilder ret = new StringBuilder();
        for (String path : paths) {
            String content = await(gitHubRepoClient.readFile(token, path));
            ret.append("=== ").append(path).append(" ===\n")
               .append(content != null ? content : "(no such file)")
               .append("\n");
        }
        return ret.toString();
    }

    @Tool(description = """
            Writes files to the app's repository as one commit on its default branch, which deploys the app. Each file \
            carries its full new content; a file with null content is deleted. Write every change of a step in one call, \
            then call waitForDeployment.""")
    public String writeFiles(@ToolParam(description = "The commit message") String message,
                             @ToolParam(description = "The files to write or delete") List<RepoFile> files,
                             ToolContext toolContext) {
        BuilderTurn turn = turn(toolContext);
        Validate.notEmpty(files, "files are required");
        ProjectRepoToken token = repoToken(turn);
        turn.events().accept(BuildEvent.progress("Committing " + files.size() + (files.size() == 1 ? " file" : " files")));
        String commitSha = await(gitHubRepoClient.commit(token, message, files));
        turn.session().setLastCommitSha(commitSha);
        return "Committed " + commitSha + "; its deployment has started.";
    }

    @Tool(description = """
            Waits until the deployment of the last commit writeFiles made has finished, and returns its outcome: the \
            URLs the app's UIs serve at once it is running, or why it failed. A failed deployment names the error to fix.""")
    public String waitForDeployment(ToolContext toolContext) {
        BuilderTurn turn = turn(toolContext);
        BuilderSession session = turn.session();
        String commitSha = session.getLastCommitSha();
        Validate.validState(commitSha != null, "Nothing has been committed yet");
        long deadline = System.nanoTime() + DEPLOYMENT_TIMEOUT.toNanos();
        DeploymentStatusType reported = null;
        String ret = null;
        while (ret == null && System.nanoTime() < deadline) {
            ProjectDeployment deployment = callAs(turn, () -> projectService.findDeployment(session.getProjectId())
                                                                            .toCompletionStage());
            DeploymentState observed = deployment != null ? deployment.getState().getObserved() : null;
            if (observed != null && observed.phase() != reported) {
                reported = observed.phase();
                turn.events().accept(BuildEvent.progress("Deploying: " + observed.phase().name().toLowerCase()));
            }
            if (observed != null && observed.phase() == DeploymentStatusType.FAILED) {
                ret = "The deployment failed: " + deployment.getFailureMessage();
            } else if (observed != null && observed.phase() == DeploymentStatusType.RUNNING
                       && commitSha.equals(observed.commitSha())) {
                ret = uiOutcome(turn, commitSha);
            } else {
                await(vertx.timer(DEPLOYMENT_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS));
            }
        }
        return ret != null ? ret : "The deployment has not finished after " + DEPLOYMENT_TIMEOUT.toMinutes() + " minutes";
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
            ret = await(docsClient.get(443, DOCS_HOST, DOCS_PATH + page)
                                  .ssl(true)
                                  .send()
                                  .map(resp -> resp.statusCode() == 200
                                          ? resp.bodyAsString()
                                          : "The page could not be read: HTTP " + resp.statusCode()));
        }
        return ret;
    }

    // the UI deployments of the commit, once each serves it
    private String uiOutcome(BuilderTurn turn, String commitSha) {
        String projectId = turn.session().getProjectId();
        List<UiDeployment> uis = callAs(turn, () -> uiDeploymentService.findAllForProject(projectId).toCompletionStage());
        List<String> lines = new ArrayList<>();
        String appUrl = null;
        for (UiDeployment ui : uis) {
            DeploymentState observed = ui.getState().getObserved();
            boolean serving = observed != null && commitSha.equals(observed.commitSha())
                              && observed.phase() == DeploymentStatusType.READY;
            lines.add("UI " + ui.getName() + ": " + (serving ? "serving at " + ui.getUrl()
                                                              : "not serving this commit yet (" + describe(observed) + ")"));
            if (serving && appUrl == null) {
                appUrl = ui.getUrl();
            }
        }
        if (appUrl != null) {
            turn.session().getConversation().setAppUrl(appUrl);
            turn.events().accept(BuildEvent.appReady(appUrl));
        }
        return "The deployment is running commit " + commitSha + ".\n"
               + (lines.isEmpty() ? "The project has no UI." : String.join("\n", lines));
    }

    private static String describe(DeploymentState state) {
        return state == null ? "no state yet" : state.phase().name().toLowerCase();
    }

    private Project newProject(BuilderTurn turn, Application application, String name, String description) {
        Project ret = new Project();
        ret.setName(name);
        ret.setDescription(description);
        ret.setOrganizationId(turn.participant().getOrganizationId());
        ret.setApplicationId(application.getId());
        ret.setSourceOfTruth(ProjectType.TYPESCRIPT);
        ret.setRepoPrivate(true);
        return ret;
    }

    private ProjectRepoToken repoToken(BuilderTurn turn) {
        String projectId = turn.session().getProjectId();
        Validate.validState(projectId != null, "The app does not exist yet; call createApp first");
        // the organization is the one the management gateway authenticated the chat call's participant for
        return callAs(turn, () -> repoCredentialService
                .issueWriteToken(turn.participant().getOrganizationId(), projectId)
                .toCompletionStage());
    }

    private <T> T callAs(BuilderTurn turn, Supplier<? extends CompletionStage<T>> call) {
        try {
            return securityContext.callAs(turn.participant(), call).get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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

    private static BuilderTurn turn(ToolContext toolContext) {
        return (BuilderTurn) toolContext.getContext().get(TURN);
    }
}
