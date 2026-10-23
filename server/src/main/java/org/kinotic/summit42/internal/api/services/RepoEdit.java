package org.kinotic.summit42.internal.api.services;

import org.springframework.ai.tool.annotation.ToolParam;

/**
 * A change to part of a file of a commit the app builder writes: text of the file on the default branch, which occurs
 * in it exactly once, and the text that replaces it.
 */
public record RepoEdit(@ToolParam(description = "The file's path in the repository, such as packages/ui/src/App.vue")
                       String path,
                       @ToolParam(description = "Text of the file to replace, exactly as it is in the file and long enough to occur only once")
                       String oldText,
                       @ToolParam(required = false, description = "The text that replaces it; omit it to remove the text")
                       String newText) {
}
