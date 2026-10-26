package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.ReconcileState;
import org.kinotic.management.api.model.ProjectRepoToken;
import org.kinotic.management.api.model.deployment.DeploymentState;
import org.kinotic.management.api.model.deployment.DeploymentStatusType;
import org.kinotic.management.api.model.deployment.MicroserviceDeployment;
import org.kinotic.management.api.model.deployment.ProjectDeployment;
import org.kinotic.management.api.model.deployment.UiDeployment;
import org.kinotic.management.api.model.telemetry.LogQuery;
import org.kinotic.management.api.services.ProjectService;
import org.kinotic.management.api.services.deployment.MicroserviceDeploymentService;
import org.kinotic.management.api.services.deployment.UiDeploymentService;
import org.kinotic.management.api.services.github.ProjectRepoCredentialService;
import org.kinotic.management.api.services.telemetry.LogService;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuildStepType;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The tools Claude builds the turn's app with. Each works on the project of the {@link BuilderTurn} and acts for its
 * signed-in user, so Kinotic authorizes every call against their organization, and reports what it does as
 * {@link BuildEvent#progress(BuildStepType, String)} events.
 */
@Component
@RequiredArgsConstructor
public class AppBuilderTools {

    /**
     * The {@link ToolContext} key of the {@link BuilderTurn} a tool call belongs to.
     */
    public static final String TURN = "turn";

    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEPLOYMENT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEPLOYMENT_POLL_INTERVAL = Duration.ofSeconds(5);
    // a repository token is renewed this long before it expires, so a commit never starts with one about to stop working
    private static final Duration TOKEN_RENEWAL_MARGIN = Duration.ofMinutes(5);
    // the paths a readFiles step names before summing up the rest
    private static final int MAX_PATHS_DESCRIBED = 3;
    private static final Duration LOG_WINDOW = Duration.ofHours(1);
    // the newest lines readAppLogs answers for each microservice, and the characters it keeps of each line
    private static final int MAX_LOG_LINES = 200;
    private static final int MAX_LOG_LINE_LENGTH = 1_000;
    // the characters of a service's result callAppService answers with
    private static final int MAX_RESULT_LENGTH = 20_000;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String DOCS_HOST = "raw.githubusercontent.com";
    private static final String DOCS_PATH = "/kinotic-ai/kinotic/develop/website/content/";
    private static final List<String> DOC_PAGES = List.of(
            "01.getting-started/01.introduction.md",
            "01.getting-started/02.quick-start.md",
            "03.apps/03.application-structure/01.overview.md",
            "03.apps/03.application-structure/02.applications-and-projects.md",
            "03.apps/03.application-structure/03.artifact-types.md",
            "03.apps/04.services/01.overview.md",
            "03.apps/04.services/02.publishing-services.md",
            "03.apps/04.services/03.service-proxies.md",
            "03.apps/04.services/04.streaming.md",
            "03.apps/05.persistence/01.overview.md",
            "03.apps/05.persistence/02.defining-entities.md",
            "03.apps/05.persistence/03.entity-decorators.md",
            "03.apps/05.persistence/04.crud-operations.md",
            "03.apps/05.persistence/05.named-queries.md",
            "03.apps/05.persistence/06.multi-tenancy.md",
            "03.apps/05.persistence/07.migrations.md",
            "03.apps/06.security/01.authentication.md",
            "03.apps/07.deployment/01.workflow.md",
            "03.apps/07.deployment/02.environments.md",
            "03.apps/07.deployment/03.push-to-deploy.md",
            "03.apps/08.cli-reference.md",
            "03.apps/09.reference/01.decorators.md",
            "03.apps/09.reference/02.migration-sql-grammar.md");

    private final Vertx vertx;
    private final SecurityContext securityContext;
    private final ProjectService projectService;
    private final UiDeploymentService uiDeploymentService;
    private final MicroserviceDeploymentService microserviceDeploymentService;
    private final LogService logService;
    private final AppServiceClient appServiceClient;
    private final ProjectRepoCredentialService repoCredentialService;
    private final GitHubRepoClient gitHubRepoClient;
    private final WebClient webClient;
    private final AppSignInService appSignInService;

    @Tool(description = "Lists the path of every file in the app's repository on its default branch.")
    public String listFiles(ToolContext toolContext) {
        return act(toolContext, BuildStepType.READING, "Looking through the app's files", this::describeFiles);
    }

    @Tool(description = "Reads files of the app's repository, returning each file's content under a header naming it.")
    public String readFiles(@ToolParam(description = "Repository paths of the files to read") List<String> paths,
                            ToolContext toolContext) {
        return act(toolContext, BuildStepType.READING, "Reading " + describePaths(paths), turn -> {
            ProjectRepoToken token = repoToken(turn);
            List<Future<String>> contents = paths.stream().map(path -> gitHubRepoClient.readFile(token, path)).toList();
            await(Future.all(contents));
            StringBuilder ret = new StringBuilder();
            for (int i = 0; i < paths.size(); i++) {
                String content = contents.get(i).result();
                ret.append("=== ").append(paths.get(i)).append(" ===\n")
                   .append(content != null ? content : "(no such file)")
                   .append("\n");
            }
            return ret.toString();
        });
    }

    @Tool(description = """
            Commits changes to the app's repository as one commit on its default branch, which deploys the app. `edits` \
            change part of an existing file, each replacing text that occurs exactly once in it; use them for every \
            change to an existing file that keeps most of it, as they are much faster than writing the file whole. \
            `files` write new files and rewrites with their full content, and delete a file given without content. \
            Put every change of a step in one call, then call waitForDeployment. Nothing is committed when an edit's \
            text is not found exactly once.""")
    public String writeFiles(@ToolParam(description = "The commit message") String message,
                             @ToolParam(required = false, description = "The files to write whole or delete") List<RepoFile> files,
                             @ToolParam(required = false, description = "The changes to parts of existing files, applied in order") List<RepoEdit> edits,
                             ToolContext toolContext) {
        List<RepoFile> written = Objects.requireNonNullElse(files, List.of());
        List<RepoEdit> changes = Objects.requireNonNullElse(edits, List.of());
        Validate.isTrue(!written.isEmpty() || !changes.isEmpty(), "files or edits are required");
        long count = Stream.concat(written.stream().map(RepoFile::path), changes.stream().map(RepoEdit::path)).distinct().count();
        return act(toolContext, BuildStepType.COMMITTING, "Committing " + count + (count == 1 ? " file: " : " files: ") + message, turn -> {
            ProjectRepoToken token = repoToken(turn);
            List<RepoFile> commit = new ArrayList<>(written);
            commit.addAll(edited(token, written, changes));
            String commitSha = await(gitHubRepoClient.commit(token, message, commit));
            turn.getSession().setLastCommitSha(commitSha);
            return "Committed " + commitSha + "; its deployment has started.";
        });
    }

    @Tool(description = """
            Waits until the deployment of the last commit writeFiles made has finished and its UIs serve it, and returns \
            its outcome: the URLs the app's UIs serve at, or why it failed. A failed deployment names the error to fix.""")
    public String waitForDeployment(ToolContext toolContext) {
        return act(toolContext, BuildStepType.DEPLOYING, "Waiting for the deployment", turn -> {
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

    @Tool(description = """
            Reads the logs of the app's microservices from the last hour, oldest first: what each printed and the errors \
            it raised, and why a microservice is not running when it is not. Read them after a deployment that changes a \
            microservice, and whenever something the app does fails or finds nothing, before changing its code.""")
    public String readAppLogs(ToolContext toolContext) {
        return act(toolContext, BuildStepType.CHECKING, "Reading the app's logs", turn -> {
            List<MicroserviceDeployment> microservices =
                    callAs(turn, () -> microserviceDeploymentService.findAllForProject(turn.getProjectId()).toCompletionStage());
            String ret;
            if (microservices.isEmpty()) {
                ret = "The app has no deployed microservice.";
            } else {
                long end = System.currentTimeMillis();
                StringBuilder logs = new StringBuilder();
                for (MicroserviceDeployment microservice : microservices) {
                    logs.append("=== ").append(microservice.getName()).append(" ===\n");
                    if (microservice.getFailureMessage() != null) {
                        logs.append("Not running as it should: ").append(microservice.getFailureMessage()).append("\n");
                    }
                    if (microservice.getWorkloadId() == null) {
                        logs.append("(not started yet)\n");
                    } else {
                        LogQuery query = new LogQuery().setOrganizationId(microservice.getOrganizationId())
                                                       .setWorkloadId(microservice.getWorkloadId())
                                                       .setStart(end - LOG_WINDOW.toMillis())
                                                       .setEnd(end + 1)
                                                       .setLimit(MAX_LOG_LINES);
                        logs.append(logLines(callAs(turn, () -> logService.history(query).toCompletionStage())));
                    }
                }
                ret = logs.toString();
            }
            return ret;
        });
    }

    @Tool(description = """
            Calls a function of a service the app's microservice publishes, as the signed-in user, and returns its \
            result as JSON or the error it failed with. Call it after a deployment to check that a service works, with \
            the arguments a UI of the app would send, and read the app's logs when it fails or returns nothing. The \
            signed-in user is a member of the app's organization, not one of the app's own users.""")
    public String callAppService(@ToolParam(description = "The service's namespace and name as the microservice publishes it, such as recipeapp.RecipeSearchService")
                                 String service,
                                 @ToolParam(description = "The function to call, such as searchRecipes") String function,
                                 @ToolParam(description = "The function's arguments as a JSON array, in order, such as [\"enchiladas\"]")
                                 String arguments,
                                 ToolContext toolContext) {
        Validate.isTrue(isJsonArray(arguments), "The arguments must be a JSON array, such as [\"enchiladas\"] or []");
        return act(toolContext, BuildStepType.CHECKING, "Testing the app", turn -> {
            String result = await(appServiceClient.call(turn.getParticipant(), turn.getSession().getApplicationKey(),
                                                        service, function, arguments));
            String ret;
            if (result == null) {
                ret = "The function returned nothing.";
            } else if (result.length() > MAX_RESULT_LENGTH) {
                ret = result.substring(0, MAX_RESULT_LENGTH) + "\n(the result is cut off here; it is " + result.length() + " characters long)";
            } else {
                ret = result;
            }
            return ret;
        });
    }

    @Tool(description = """
            Says who can sign in to the app and how: only the people the user invites on the app's Users page in \
            Summit 42, or anyone who signs up, and whether with an email and a password, through the app's sign-in \
            providers, or not at all yet.""")
    public String readSignIn(ToolContext toolContext) {
        return act(toolContext, BuildStepType.READING, "Checking who can sign in",
                   turn -> await(appSignInService.find(turn.getParticipant(),
                                                       turn.getSession().getApplicationKey().applicationId())).describe());
    }

    @Tool(description = """
            Sets who can sign in to the app. With openSignUp true, anyone can sign up at the app with an email and a \
            password or through any sign-in provider the app offers; with false, only the people the user invites on \
            the app's Users page in Summit 42 sign in. Call it when the user says who should be able to sign in. It \
            answers how people sign in from then on.""")
    public String configureSignIn(@ToolParam(description = "true to let anyone sign up, false to keep the app to the people invited")
                                  boolean openSignUp,
                                  ToolContext toolContext) {
        return act(toolContext, BuildStepType.CONFIGURING, openSignUp ? "Letting anyone sign up" : "Keeping sign-in to the people invited",
                   turn -> await(appSignInService.setOpenSignUp(turn.getParticipant(),
                                                                turn.getSession().getApplicationKey().applicationId(),
                                                                openSignUp)).describe());
    }

    @Tool(description = "Reads a page of Kinotic's documentation for building apps. Pages: "
                        + "01.getting-started/01.introduction.md, 01.getting-started/02.quick-start.md, "
                        + "03.apps/03.application-structure/*, 03.apps/04.services/*, 03.apps/05.persistence/*, "
                        + "03.apps/06.security/01.authentication.md, 03.apps/07.deployment/*, 03.apps/08.cli-reference.md, "
                        + "03.apps/09.reference/01.decorators.md, 03.apps/09.reference/02.migration-sql-grammar.md; "
                        + "call it with an unknown page to list every page.")
    public String readKinoticDocs(@ToolParam(description = "The page's path, such as 03.apps/05.persistence/02.defining-entities.md")
                                  String page,
                                  ToolContext toolContext) {
        String ret;
        if (!DOC_PAGES.contains(page)) {
            ret = "Unknown page. The pages are:\n" + String.join("\n", DOC_PAGES);
        } else {
            // 03.apps/05.persistence/02.defining-entities.md reads as "persistence / defining entities"
            String title = page.replaceAll("^[^/]+/", "").replaceAll("\\d+\\.", "").replace(".md", "").replace("/", " / ").replace('-', ' ');
            ret = act(toolContext, BuildStepType.READING, "Reading Kinotic's docs on " + title,
                      turn -> await(webClient.get(443, DOCS_HOST, DOCS_PATH + page)
                                             .ssl(true)
                                             .send()
                                             .map(resp -> resp.statusCode() == 200
                                                     ? resp.bodyAsString()
                                                     : "The page could not be read: HTTP " + resp.statusCode())));
        }
        return ret;
    }

    @Tool(description = """
            Reads one of the Kinotic skills the system prompt lists: its SKILL.md, or another of its files such as \
            references/decorators.md. The answer ends with the paths of the skill's other files.""")
    public String readSkill(@ToolParam(description = "The skill's name, such as frontend") String skill,
                            @ToolParam(required = false,
                                       description = "The file's path in the skill; omit it to read SKILL.md")
                            String file,
                            ToolContext toolContext) {
        String step = file == null || file.isBlank()
                ? "Reading the " + skill + " skill"
                : "Reading " + file + " of the " + skill + " skill";
        return act(toolContext, BuildStepType.READING, step, turn -> turn.getSkills().read(skill, file));
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

    // the log lines of a Loki query_range response, oldest first, each with the time it was logged
    private static String logLines(Buffer loki) {
        List<JsonArray> entries = new ArrayList<>();
        JsonArray streams = loki.toJsonObject().getJsonObject("data", new JsonObject()).getJsonArray("result", new JsonArray());
        for (int i = 0; i < streams.size(); i++) {
            JsonArray values = streams.getJsonObject(i).getJsonArray("values", new JsonArray());
            for (int j = 0; j < values.size(); j++) {
                entries.add(values.getJsonArray(j));
            }
        }
        // each entry is [nanoseconds since the epoch, line], the nanoseconds as a string
        entries.sort(Comparator.comparingLong(entry -> Long.parseLong(entry.getString(0))));
        StringBuilder ret = new StringBuilder();
        if (entries.isEmpty()) {
            ret.append("(nothing logged in the last ").append(LOG_WINDOW.toMinutes()).append(" minutes)\n");
        } else if (entries.size() >= MAX_LOG_LINES) {
            ret.append("(the newest ").append(MAX_LOG_LINES).append(" lines)\n");
        }
        for (JsonArray entry : entries) {
            String line = entry.getString(1);
            ret.append(LOG_TIME.format(Instant.EPOCH.plusNanos(Long.parseLong(entry.getString(0))))).append(' ')
               .append(line.length() > MAX_LOG_LINE_LENGTH ? line.substring(0, MAX_LOG_LINE_LENGTH) + "…" : line)
               .append("\n");
        }
        return ret.toString();
    }

    private static boolean isJsonArray(String json) {
        boolean ret;
        try {
            ret = json != null && Json.decodeValue(json) instanceof JsonArray;
        } catch (DecodeException e) {
            ret = false;
        }
        return ret;
    }

    // each file the edits change, with its edits applied in order to its content on the default branch
    private List<RepoFile> edited(ProjectRepoToken token, List<RepoFile> written, List<RepoEdit> edits) {
        Map<String, List<RepoEdit>> byPath = edits.stream().collect(Collectors.groupingBy(RepoEdit::path, LinkedHashMap::new, Collectors.toList()));
        written.forEach(file -> Validate.isTrue(!byPath.containsKey(file.path()),
                                                "%s is both written whole and edited; do one or the other", file.path()));
        List<Future<RepoFile>> ret = byPath.entrySet().stream()
                                           .map(entry -> gitHubRepoClient.readFile(token, entry.getKey())
                                                                         .map(content -> new RepoFile(entry.getKey(),
                                                                                                      applyEdits(entry.getKey(), content, entry.getValue()))))
                                           .toList();
        await(Future.all(ret));
        return ret.stream().map(Future::result).toList();
    }

    private static String applyEdits(String path, String content, List<RepoEdit> edits) {
        Validate.isTrue(content != null, "%s does not exist; write it whole in files", path);
        String ret = content;
        for (RepoEdit edit : edits) {
            Validate.notEmpty(edit.oldText(), "An edit of %s has no text to replace", path);
            int at = ret.indexOf(edit.oldText());
            Validate.isTrue(at >= 0, "The text to replace in %s was not found; read the file again", path);
            Validate.isTrue(ret.indexOf(edit.oldText(), at + 1) < 0,
                            "The text to replace in %s occurs more than once; include more of the text around it", path);
            ret = ret.substring(0, at) + Objects.requireNonNullElse(edit.newText(), "") + ret.substring(at + edit.oldText().length());
        }
        return ret;
    }

    // the first few paths, so a step reading many files stays one line
    private static String describePaths(List<String> paths) {
        String ret;
        if (paths == null || paths.isEmpty()) {
            ret = "files";
        } else if (paths.size() <= MAX_PATHS_DESCRIBED) {
            ret = String.join(", ", paths);
        } else {
            ret = String.join(", ", paths.subList(0, MAX_PATHS_DESCRIBED)) + " and " + (paths.size() - MAX_PATHS_DESCRIBED) + " more";
        }
        return ret;
    }

    // the project's deployment once it has finished deploying the commit, or null when it has not by the deadline
    private ProjectDeployment awaitDeployment(BuilderTurn turn, String commitSha, long deadline) {
        String projectId = turn.getProjectId();
        DeploymentStatusType reported = null;
        ProjectDeployment ret = null;
        while (ret == null && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            ProjectDeployment deployment = callAs(turn, () -> projectService.findDeployment(projectId).toCompletionStage());
            ReconcileState<DeploymentState> state = deployment != null ? deployment.getState() : null;
            boolean intended = state != null && state.getDesired() != null && commitSha.equals(state.getDesired().commitSha());
            if (intended && state.getObserved() != null && state.getObserved().phase() != reported) {
                reported = state.getObserved().phase();
                turn.getEvents().accept(BuildEvent.progress(BuildStepType.DEPLOYING, "Deploying: " + reported.name().toLowerCase()));
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
            turn.getEvents().accept(BuildEvent.progress(BuildStepType.DEPLOYING, "Publishing the app's pages"));
        }
        while (!allServe(uis, commitSha) && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
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
            turn.getEvents().accept(BuildEvent.appReady(appUrl));
        }
        return "The deployment is running commit " + commitSha + ".\n"
               + (lines.isEmpty() ? "The commit has no UI." : String.join("\n", lines));
    }

    // the UI deployments the commit publishes; one the commit dropped is meant to be ORPHANED instead
    private List<UiDeployment> commitUis(BuilderTurn turn, String commitSha) {
        String projectId = turn.getProjectId();
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
        ProjectRepoToken ret = session.getRepoToken();
        if (ret == null || Instant.now().plus(TOKEN_RENEWAL_MARGIN).isAfter(ret.getExpiresAt())) {
            ret = callAs(turn, () -> repoCredentialService.issueWriteToken(turn.getProjectId()).toCompletionStage());
            session.setRepoToken(ret);
        }
        return ret;
    }

    // holds the turn while the tool runs, so a cancelled turn keeps its session until the running tool returns
    private static String act(ToolContext toolContext, BuildStepType stepType, String step, Function<BuilderTurn, String> work) {
        BuilderTurn turn = (BuilderTurn) toolContext.getContext().get(TURN);
        Validate.validState(turn.hold(), "The turn has ended");
        try {
            turn.getEvents().accept(BuildEvent.progress(stepType, step));
            synchronized (turn.getSession()) {
                // the app was deleted, and one created again under its name has the same project id
                Validate.validState(!turn.getSession().isDeleted(), "The app was deleted");
            }
            return work.apply(turn);
        } finally {
            turn.release();
        }
    }

    private <T> T callAs(BuilderTurn turn, Supplier<? extends CompletionStage<T>> call) {
        return get(securityContext.callAs(turn.getParticipant(), call));
    }

    private static <T> T await(Future<T> future) {
        return get(future.toCompletionStage().toCompletableFuture());
    }

    // tools run on Spring AI's boundedElastic workers, so blocking on a future is allowed here. A cancelled turn
    // interrupts its tool's thread, yet the tool runs to its end, each call waited for up to CALL_TIMEOUT, so a
    // commit it started is recorded before the turn is released; the restored interrupt stops the
    // deployment polling
    private static <T> T get(CompletableFuture<T> future) {
        long deadline = System.nanoTime() + CALL_TIMEOUT.toNanos();
        boolean interrupted = false;
        boolean done = false;
        T ret = null;
        try {
            while (!done) {
                try {
                    ret = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    done = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(rootMessage(e), e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return ret;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
    }
}
