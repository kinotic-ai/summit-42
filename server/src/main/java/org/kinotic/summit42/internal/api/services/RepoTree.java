package org.kinotic.summit42.internal.api.services;

import java.util.List;

/**
 * The files on a repository's default branch.
 *
 * @param paths     the path of every file GitHub listed
 * @param truncated whether the repository holds more files than GitHub lists in one answer, so some are missing
 *                  from {@code paths}
 */
public record RepoTree(List<String> paths, boolean truncated) {
}
