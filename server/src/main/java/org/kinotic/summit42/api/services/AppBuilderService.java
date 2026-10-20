package org.kinotic.summit42.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.Conversation;
import reactor.core.publisher.Flux;

/**
 * The chat through which a signed-in user has Claude build, change and deploy their organization's app. Each
 * organization has one conversation, and the app it builds is a regular Kinotic application of that organization.
 */
@Publish
public interface AppBuilderService {

    /**
     * Sends the user's message and streams Claude's turn: its reply as {@link org.kinotic.summit42.api.model.BuildEventType#TEXT}
     * chunks, interleaved with what it is doing to the app. The stream completes when the turn is over, and ends
     * with an {@link org.kinotic.summit42.api.model.BuildEventType#ERROR} event when the turn fails.
     *
     * @param message what the user asked for
     * @return the turn's events
     */
    Flux<BuildEvent> chat(String message);

    /**
     * @return the organization's conversation so far and the URL of its deployed app, if there is one
     */
    Future<Conversation> findConversation();

    /**
     * Clears the organization's conversation, so the next message starts a new one. The app and its repository
     * are kept.
     */
    Future<Void> resetConversation();
}
