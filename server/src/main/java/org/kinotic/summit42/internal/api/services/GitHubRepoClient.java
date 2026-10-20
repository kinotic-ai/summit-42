package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.kinotic.management.api.model.ProjectRepoToken;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and commits the files of a project's GitHub repository through GitHub's REST API, with a token from
 * {@code ProjectRepoCredentialService}.
 */
@Component
public class GitHubRepoClient {

    private static final String API_HOST = "api.github.com";
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String RAW = "application/vnd.github.raw+json";
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";
    private static final String REGULAR_FILE_MODE = "100644";

    private final WebClient webClient;

    public GitHubRepoClient(Vertx vertx) {
        this.webClient = WebClient.create(vertx, new WebClientOptions().setUserAgent("summit-42"));
    }

    /**
     * @return the path of every file on the repository's default branch
     */
    public Future<List<String>> listFiles(ProjectRepoToken token) {
        String repo = repoFullName(token);
        return request(HttpMethod.GET, "/repos/" + repo + "/git/trees/" + token.getDefaultBranch() + "?recursive=1", token, ACCEPT)
                .send()
                .compose(resp -> requireStatus(resp, 200, "list files"))
                .map(resp -> {
                    List<String> ret = new ArrayList<>();
                    JsonArray tree = resp.bodyAsJsonObject().getJsonArray("tree", new JsonArray());
                    for (int i = 0; i < tree.size(); i++) {
                        JsonObject entry = tree.getJsonObject(i);
                        if ("blob".equals(entry.getString("type"))) {
                            ret.add(entry.getString("path"));
                        }
                    }
                    return ret;
                });
    }

    /**
     * @return the file's content on the default branch, or null when there is no such file
     */
    public Future<String> readFile(ProjectRepoToken token, String path) {
        String repo = repoFullName(token);
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20");
        return request(HttpMethod.GET, "/repos/" + repo + "/contents/" + encodedPath + "?ref=" + token.getDefaultBranch(), token, RAW)
                .send()
                .compose(resp -> resp.statusCode() == 404
                        ? Future.succeededFuture(null)
                        : requireStatus(resp, 200, "read " + path).map(HttpResponse::bodyAsString));
    }

    /**
     * Commits the files to the default branch in one commit on top of its head, which deploys the project.
     *
     * @return the new commit's sha
     */
    public Future<String> commit(ProjectRepoToken token, String message, List<RepoFile> files) {
        String repo = repoFullName(token);
        String ref = "heads/" + token.getDefaultBranch();
        return request(HttpMethod.GET, "/repos/" + repo + "/git/ref/" + ref, token, ACCEPT)
                .send()
                .compose(resp -> requireStatus(resp, 200, "read the branch head"))
                .map(resp -> resp.bodyAsJsonObject().getJsonObject("object").getString("sha"))
                .compose(head -> request(HttpMethod.GET, "/repos/" + repo + "/git/commits/" + head, token, ACCEPT)
                        .send()
                        .compose(resp -> requireStatus(resp, 200, "read the head commit"))
                        .map(resp -> resp.bodyAsJsonObject().getJsonObject("tree").getString("sha"))
                        .compose(baseTree -> request(HttpMethod.POST, "/repos/" + repo + "/git/trees", token, ACCEPT)
                                .sendJsonObject(new JsonObject().put("base_tree", baseTree).put("tree", treeEntries(files))))
                        .compose(resp -> requireStatus(resp, 201, "write the tree"))
                        .compose(resp -> request(HttpMethod.POST, "/repos/" + repo + "/git/commits", token, ACCEPT)
                                .sendJsonObject(new JsonObject()
                                                        .put("message", message)
                                                        .put("tree", resp.bodyAsJsonObject().getString("sha"))
                                                        .put("parents", new JsonArray().add(head)))))
                .compose(resp -> requireStatus(resp, 201, "write the commit"))
                .map(resp -> resp.bodyAsJsonObject().getString("sha"))
                .compose(commit -> request(HttpMethod.PATCH, "/repos/" + repo + "/git/refs/" + ref, token, ACCEPT)
                        .sendJsonObject(new JsonObject().put("sha", commit).put("force", false))
                        .compose(resp -> requireStatus(resp, 200, "move the branch"))
                        .map(commit));
    }

    private static JsonArray treeEntries(List<RepoFile> files) {
        JsonArray ret = new JsonArray();
        for (RepoFile file : files) {
            JsonObject entry = new JsonObject()
                    .put("path", file.path())
                    .put("mode", REGULAR_FILE_MODE)
                    .put("type", "blob");
            // a null sha removes the path from the base tree
            if (file.content() == null) {
                entry.putNull("sha");
            } else {
                entry.put("content", file.content());
            }
            ret.add(entry);
        }
        return ret;
    }

    // the clone URL is https://github.com/<owner>/<repo>.git
    private static String repoFullName(ProjectRepoToken token) {
        String url = token.getCloneUrl();
        return url.substring("https://github.com/".length(), url.length() - ".git".length());
    }

    private HttpRequest<Buffer> request(HttpMethod method, String path, ProjectRepoToken token, String accept) {
        return webClient.request(method, 443, API_HOST, path)
                        .ssl(true)
                        .putHeader("Authorization", "Bearer " + token.getToken())
                        .putHeader("Accept", accept)
                        .putHeader(API_VERSION_HEADER, API_VERSION);
    }

    private static Future<HttpResponse<Buffer>> requireStatus(HttpResponse<Buffer> resp, int status, String operation) {
        Future<HttpResponse<Buffer>> ret;
        if (resp.statusCode() == status) {
            ret = Future.succeededFuture(resp);
        } else {
            ret = Future.failedFuture(new IllegalStateException("GitHub refused to " + operation + ": HTTP "
                                                                 + resp.statusCode() + " " + resp.bodyAsString()));
        }
        return ret;
    }
}
