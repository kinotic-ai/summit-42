package org.kinotic.summit42.fakes;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.ext.web.client.impl.WebClientInternal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Answers the GitHub API calls of Summit 42's {@link WebClient} on a local port: those that read Kinotic's agent
 * plugin, for the plugin's ref {@link #REF}, which names {@link #PLUGIN_COMMIT}, and those that read and commit the
 * files of an app's repository, {@link #APP_REPOSITORY}, whose default branch is {@link #REF}. Refuses every call
 * while a test makes it fail.
 */
public class FakeGitHub implements AutoCloseable {

    public static final String REF = "main";
    /**
     * The commit {@link #REF} names.
     */
    public static final String PLUGIN_COMMIT = "a1b2c3";
    /**
     * The plugin's files at {@link #PLUGIN_COMMIT}: two skills Summit 42 reads, one it leaves out by name and one it
     * leaves out for having no description.
     */
    public static final Map<String, String> PLUGIN_FILES = Map.of(
            "README.md", "# Kinotic agent plugin",
            "skills/frontend/SKILL.md", """
                    ---
                    name: frontend
                    description: >
                      How a UI signs in
                      and connects to Kinotic.
                    ---

                    # Frontends

                    Sign in with login(), then connect.""",
            "skills/frontend/references/vite.md", "# Vite\n\nAlias the domain package to its source.",
            "skills/services/SKILL.md", """
                    ---
                    name: services
                    description: |
                      How to publish
                      a service.
                    ---
                    # Services""",
            "skills/create-app/SKILL.md", """
                    ---
                    name: create-app
                    description: Create an application through the Kinotic MCP server.
                    ---
                    # Creating an app""",
            "skills/notes/SKILL.md", "# Notes without frontmatter");
    /**
     * The full name of the app's repository, whose clone URL is https://github.com/acme/todo.git.
     */
    public static final String APP_REPOSITORY = "acme/todo";
    private static final String API_HOST = "api.github.com";
    private static final String REPOSITORY_PATH = "/repos/kinotic-ai/agent-plugin";
    private static final String APP_PATH = "/repos/" + APP_REPOSITORY;

    private final HttpServer server;
    private final byte[] zipball = zip(PLUGIN_COMMIT, PLUGIN_FILES);
    private volatile boolean failing;
    // the app's repository: the files on its default branch, the trees and commits written, and the commits' messages
    private final Map<String, String> appFiles = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> trees = new ConcurrentHashMap<>();
    private final List<String> commitMessages = new CopyOnWriteArrayList<>();
    private final AtomicInteger shas = new AtomicInteger();
    private volatile String head = "commit-0";

    /**
     * Starts the server and sends the client's calls to GitHub's API to it.
     */
    public FakeGitHub(Vertx vertx, WebClient webClient) {
        server = vertx.createHttpServer()
                      .requestHandler(this::answer)
                      .listen(0)
                      .toCompletionStage()
                      .toCompletableFuture()
                      .join();
        ((WebClientInternal) webClient).addInterceptor(this::reroute);
    }

    /**
     * Makes every call fail with an HTTP 503, or answer again.
     */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    /**
     * Empties the app's repository, then puts the files given on its default branch.
     */
    public void resetAppRepository(Map<String, String> files) {
        appFiles.clear();
        appFiles.putAll(files);
        commitMessages.clear();
    }

    /**
     * @return the files on the default branch of the app's repository
     */
    public Map<String, String> appFiles() {
        return Map.copyOf(appFiles);
    }

    /**
     * @return the message of each commit to the app's repository since it was last reset, in order
     */
    public List<String> commitMessages() {
        return List.copyOf(commitMessages);
    }

    @Override
    public void close() {
        server.close();
    }

    // an interceptor runs in every phase of a call and must pass each one on with next()
    private void reroute(HttpContext<?> context) {
        RequestOptions options = context.requestOptions();
        if (context.phase() == ClientPhase.CREATE_REQUEST && API_HOST.equals(options.getHost())) {
            options.setHost("127.0.0.1").setPort(server.actualPort()).setSsl(false);
        }
        context.next();
    }

    private void answer(HttpServerRequest request) {
        String path = request.path();
        String etag = "\"" + PLUGIN_COMMIT + "\"";
        if (failing) {
            request.response().setStatusCode(503).end("Unavailable");
        } else if (path.startsWith(APP_PATH + "/")) {
            request.body().onSuccess(body -> answerApp(request, path.substring(APP_PATH.length()), body));
        } else if (path.equals(REPOSITORY_PATH + "/commits/" + REF)) {
            if (etag.equals(request.getHeader("If-None-Match"))) {
                request.response().setStatusCode(304).end();
            } else {
                request.response().putHeader("ETag", etag).end(PLUGIN_COMMIT);
            }
        } else if (path.equals(REPOSITORY_PATH + "/zipball/" + PLUGIN_COMMIT)) {
            request.response().putHeader("Content-Type", "application/zip").end(Buffer.buffer(zipball));
        } else {
            request.response().setStatusCode(404).end("Not Found");
        }
    }

    // the Contents and Git Data API calls GitHubRepoClient reads and commits a repository with
    private void answerApp(HttpServerRequest request, String path, Buffer body) {
        String contentsPath = "/contents/";
        String treesPath = "/git/trees";
        String commitsPath = "/git/commits";
        if (request.method() == HttpMethod.GET && path.startsWith(contentsPath)) {
            String content = appFiles.get(path.substring(contentsPath.length()));
            if (content == null) {
                request.response().setStatusCode(404).end("Not Found");
            } else {
                request.response().end(content);
            }
        } else if (request.method() == HttpMethod.GET && path.equals("/git/ref/heads/" + REF)) {
            json(request, 200, new JsonObject().put("object", new JsonObject().put("sha", head)));
        } else if (request.method() == HttpMethod.GET && path.startsWith(commitsPath + "/")) {
            json(request, 200, new JsonObject().put("tree", new JsonObject().put("sha", "tree-of-" + path.substring(commitsPath.length() + 1))));
        } else if (request.method() == HttpMethod.GET && path.startsWith(treesPath + "/")) {
            JsonArray entries = new JsonArray();
            appFiles.keySet().forEach(file -> entries.add(new JsonObject().put("path", file).put("mode", "100644").put("type", "blob")));
            json(request, 200, new JsonObject().put("tree", entries).put("truncated", false));
        } else if (request.method() == HttpMethod.POST && path.equals(treesPath)) {
            Map<String, String> tree = new HashMap<>(appFiles);
            body.toJsonObject().getJsonArray("tree").forEach(entry -> {
                JsonObject file = (JsonObject) entry;
                if (file.containsKey("content")) {
                    tree.put(file.getString("path"), file.getString("content"));
                } else {
                    tree.remove(file.getString("path"));
                }
            });
            String sha = "tree-" + shas.incrementAndGet();
            trees.put(sha, tree);
            json(request, 201, new JsonObject().put("sha", sha));
        } else if (request.method() == HttpMethod.POST && path.equals(commitsPath)) {
            JsonObject commit = body.toJsonObject();
            String sha = "commit-" + shas.incrementAndGet();
            trees.put(sha, trees.get(commit.getString("tree")));
            commitMessages.add(commit.getString("message"));
            json(request, 201, new JsonObject().put("sha", sha));
        } else if (request.method() == HttpMethod.PATCH && path.equals("/git/refs/heads/" + REF)) {
            head = body.toJsonObject().getString("sha");
            appFiles.clear();
            appFiles.putAll(trees.get(head));
            json(request, 200, new JsonObject().put("object", new JsonObject().put("sha", head)));
        } else {
            request.response().setStatusCode(404).end("Not Found");
        }
    }

    private static void json(HttpServerRequest request, int status, JsonObject body) {
        request.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(body.encode());
    }

    // GitHub's zipball holds the files under a directory named for the repository and commit
    private static byte[] zip(String commit, Map<String, String> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            String root = "kinotic-ai-agent-plugin-" + commit + "/";
            out.putNextEntry(new ZipEntry(root));
            out.closeEntry();
            for (Map.Entry<String, String> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(root + file.getKey()));
                out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
