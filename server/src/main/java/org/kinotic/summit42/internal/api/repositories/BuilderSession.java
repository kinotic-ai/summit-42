package org.kinotic.summit42.internal.api.repositories;

import io.vertx.core.Future;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.management.api.model.ProjectRepoToken;
import org.kinotic.summit42.api.model.Conversation;

/**
 * What the app builder holds for one app: its conversation, and the state the app's chat turns share.
 */
@Getter
@Setter
@Accessors(chain = true)
public class BuilderSession {

    private final ApplicationKey applicationKey;

    private final Conversation conversation;

    /**
     * The last write token issued for the app's repository, or null before the first.
     */
    private ProjectRepoToken repoToken;

    /**
     * The commit the builder last pushed to the repository's default branch, the one the next deployment serves.
     */
    private String lastCommitSha;

    /**
     * Whether a chat turn of the app is running, so a second one, and a reset of the conversation, is refused until
     * it ends.
     */
    private boolean turnRunning;

    /**
     * Whether the session's app was deleted, after which the session's conversation is not saved and its turn's tools
     * do not run.
     */
    private boolean deleted;

    /**
     * The most recent save of the conversation, which the next save waits for so the saves complete in order.
     */
    private Future<Void> lastSave = Future.succeededFuture();

    public BuilderSession(ApplicationKey applicationKey, Conversation conversation) {
        this.applicationKey = applicationKey;
        this.conversation = conversation;
    }
}
