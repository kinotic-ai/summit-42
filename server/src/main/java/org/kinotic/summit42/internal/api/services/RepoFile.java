package org.kinotic.summit42.internal.api.services;

/**
 * A file of a commit the app builder writes: its path in the repository and its full new content, or null content
 * to delete it.
 */
public record RepoFile(String path, String content) {
}
