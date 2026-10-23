package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The skills of Kinotic's agent plugin, which teach Claude to build Kinotic apps, read from the plugin's GitHub
 * repository at {@link Summit42Properties#getKinoticPluginRepositoryUrl()} and kept up to date with the branch or tag
 * {@link Summit42Properties#getKinoticPluginRef()} as it moves.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KinoticSkills {

    // how long the skills are used before the plugin's ref is checked for a new commit again
    private static final Duration CHECK_INTERVAL = Duration.ofMinutes(5);
    private static final String UNAVAILABLE = "Summit 42 could not load Kinotic's skills from GitHub. Please try again"
                                              + " in a few minutes.";
    private static final String SKILLS_DIR = "skills/";
    private static final String FRONTMATTER_DELIMITER = "---";
    // onboards a user through the Kinotic MCP server and creates the app and its project, which Summit 42 does itself
    private static final Set<String> EXCLUDED_SKILLS = Set.of("create-app");
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";

    private final WebClient webClient;
    private final Summit42Properties properties;

    // guarded by this
    private SkillSet current;
    private String currentEtag;
    private long checkedAt;
    private Future<SkillSet> running;

    /**
     * The skills as last read. Once they were read {@link #CHECK_INTERVAL} ago, a check of the plugin for a newer
     * commit starts in the background, whose skills a later call returns.
     *
     * @return the skills; the first call waits for them to be read, and every call fails with
     * {@link BuildUnavailableException} until a read succeeds
     */
    public synchronized Future<SkillSet> current() {
        Future<SkillSet> ret;
        if (current == null) {
            ret = check().recover(e -> Future.failedFuture(new BuildUnavailableException(UNAVAILABLE)));
        } else {
            if (System.nanoTime() - checkedAt > CHECK_INTERVAL.toNanos()) {
                check();
            }
            ret = Future.succeededFuture(current);
        }
        return ret;
    }

    // the running check, or a new one; a failed check keeps the skills read before it
    private synchronized Future<SkillSet> check() {
        Future<SkillSet> ret = running;
        if (ret == null) {
            Future<SkillSet> check = fetch();
            running = check;
            ret = check;
            // registered after running is set, so a check that has already completed clears it too
            check.onComplete(result -> {
                synchronized (this) {
                    if (running == check) {
                        running = null;
                    }
                    checkedAt = System.nanoTime();
                }
                if (result.failed()) {
                    log.warn("Kinotic's skills could not be read from {}", properties.getKinoticPluginRepositoryUrl(),
                             result.cause());
                }
            });
        }
        return ret;
    }

    // the skills of the commit the ref names now; a 304 means the ref still names the commit of the current skills
    private Future<SkillSet> fetch() {
        String ref = properties.getKinoticPluginRef();
        HttpRequest<Buffer> request = request("/commits/" + ref).putHeader("Accept", "application/vnd.github.sha");
        String etag = currentEtag();
        if (etag != null) {
            request.putHeader("If-None-Match", etag);
        }
        return request.send().compose(resp -> {
            Future<SkillSet> ret;
            if (resp.statusCode() == 304) {
                ret = Future.succeededFuture(currentSkills());
            } else if (resp.statusCode() != 200) {
                ret = Future.failedFuture(refused("read the plugin's " + ref, resp));
            } else {
                String commit = resp.bodyAsString().strip();
                SkillSet known = currentSkills();
                Future<SkillSet> skills = known != null && known.version().equals(commit)
                        ? Future.succeededFuture(known)
                        : read(commit);
                ret = skills.map(read -> {
                    // the ETag is kept with the skills of its commit, so a commit whose skills failed is read again
                    synchronized (this) {
                        current = read;
                        currentEtag = resp.getHeader("ETag");
                    }
                    if (read != known) {
                        log.info("Read Kinotic's skills {} at commit {}", read.skills().keySet(), commit);
                    }
                    return read;
                });
            }
            return ret;
        });
    }

    private Future<SkillSet> read(String commit) {
        return request("/zipball/" + commit)
                .send()
                .compose(resp -> resp.statusCode() == 200
                        ? Future.succeededFuture(resp.body())
                        : Future.failedFuture(refused("download the plugin at " + commit, resp)))
                .map(zip -> parse(commit, zip.getBytes()));
    }

    private static SkillSet parse(String commit, byte[] zip) {
        Map<String, SortedMap<String, String>> filesBySkill = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                // a zipball holds the repository under a top-level directory named for the commit
                String path = StringUtils.substringAfter(entry.getName(), "/");
                if (!entry.isDirectory() && path.startsWith(SKILLS_DIR) && path.endsWith(".md")) {
                    String inSkills = path.substring(SKILLS_DIR.length());
                    String skill = StringUtils.substringBefore(inSkills, "/");
                    String file = StringUtils.substringAfter(inSkills, "/");
                    if (!file.isEmpty() && !EXCLUDED_SKILLS.contains(skill)) {
                        filesBySkill.computeIfAbsent(skill, name -> new TreeMap<>())
                                    .put(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The plugin's zipball at " + commit + " could not be read", e);
        }
        SortedMap<String, Skill> skills = new TreeMap<>();
        filesBySkill.forEach((name, files) -> {
            Skill skill = skill(name, files);
            if (skill != null) {
                skills.put(name, skill);
            }
        });
        Validate.validState(!skills.isEmpty(), "The plugin at %s has no skills", commit);
        return new SkillSet(commit, skills);
    }

    // the skill, with its SKILL.md's frontmatter read into its description, or null when it has no description
    private static Skill skill(String name, SortedMap<String, String> files) {
        String skillFile = files.get(Skill.SKILL_FILE);
        Skill ret = null;
        if (skillFile != null && skillFile.startsWith(FRONTMATTER_DELIMITER)) {
            int end = skillFile.indexOf("\n" + FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
            if (end > 0) {
                Object frontmatter = new Yaml(new SafeConstructor(new LoaderOptions()))
                        .load(skillFile.substring(FRONTMATTER_DELIMITER.length(), end));
                Object description = frontmatter instanceof Map<?, ?> map ? map.get("description") : null;
                if (description instanceof String text && !text.isBlank()) {
                    SortedMap<String, String> bodies = new TreeMap<>(files);
                    bodies.put(Skill.SKILL_FILE, skillFile.substring(end + FRONTMATTER_DELIMITER.length() + 1).strip());
                    ret = new Skill(name, StringUtils.normalizeSpace(text), bodies);
                }
            }
        }
        if (ret == null) {
            log.warn("The skill {} has no description in its {} frontmatter, so it is left out", name, Skill.SKILL_FILE);
        }
        return ret;
    }

    private HttpRequest<Buffer> request(String path) {
        return webClient.getAbs(properties.getKinoticPluginRepositoryUrl() + path)
                        .putHeader(API_VERSION_HEADER, API_VERSION);
    }

    private synchronized SkillSet currentSkills() {
        return current;
    }

    private synchronized String currentEtag() {
        return currentEtag;
    }

    private static IllegalStateException refused(String operation, HttpResponse<Buffer> resp) {
        return new IllegalStateException("GitHub refused to " + operation + ": HTTP " + resp.statusCode() + " "
                                         + resp.bodyAsString());
    }
}
