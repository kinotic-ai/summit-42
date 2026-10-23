package org.kinotic.summit42.fakes;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Answers the GitHub API calls that read Kinotic's agent plugin on a local port, for one repository whose ref
 * {@link #REF} names {@link #PLUGIN_COMMIT}, and refuses every call while a test makes it fail.
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
    private static final String REPOSITORY_PATH = "/repos/kinotic-ai/agent-plugin";

    private final HttpServer server;
    private final byte[] zipball = zip(PLUGIN_COMMIT, PLUGIN_FILES);
    private volatile boolean failing;

    public FakeGitHub(Vertx vertx) {
        server = vertx.createHttpServer()
                      .requestHandler(this::answer)
                      .listen(0)
                      .toCompletionStage()
                      .toCompletableFuture()
                      .join();
    }

    /**
     * @return the GitHub API URL of the plugin's repository
     */
    public String repositoryUrl() {
        return "http://127.0.0.1:" + server.actualPort() + REPOSITORY_PATH;
    }

    /**
     * Makes every call fail with an HTTP 503, or answer again.
     */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    @Override
    public void close() {
        server.close();
    }

    private void answer(HttpServerRequest request) {
        String path = request.path();
        String etag = "\"" + PLUGIN_COMMIT + "\"";
        if (failing) {
            request.response().setStatusCode(503).end("Unavailable");
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
