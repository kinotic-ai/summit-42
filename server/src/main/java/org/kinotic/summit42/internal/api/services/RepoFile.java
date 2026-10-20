package org.kinotic.summit42.internal.api.services;

import org.springframework.ai.tool.annotation.ToolParam;

/**
 * A file of a commit the app builder writes: its path in the repository and its full new content, or null content
 * to delete it.
 */
public record RepoFile(@ToolParam(description = "The file's path in the repository, such as packages/ui/src/App.vue")
                       String path,
                       @ToolParam(required = false,
                                  description = "The file's full new content; omit it, or pass null, to delete the file")
                       String content) {
}
