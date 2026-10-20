package org.kinotic.summit42.internal.api.repositories;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.kinotic.management.api.model.ProjectRepoToken;
import org.kinotic.summit42.api.model.Conversation;

/**
 * What the app builder holds for one organization: its conversation and the Kinotic project its app is built in.
 */
@Getter
@Setter
@Accessors(chain = true)
public class BuilderSession {

    private final String organizationId;

    private final Conversation conversation = new Conversation();

    /**
     * The project the organization's app is built in, or null until the builder creates or finds it.
     */
    private String projectId;

    /**
     * The last write token issued for the project's repository, or null before the first.
     */
    private ProjectRepoToken repoToken;

    /**
     * The commit the builder last pushed to the project's default branch, the one the next deployment serves.
     */
    private String lastCommitSha;

    /**
     * Whether a chat turn is running, so a second one, and a reset of the conversation, is refused until it ends.
     */
    private boolean turnRunning;

    public BuilderSession(String organizationId) {
        this.organizationId = organizationId;
    }
}
