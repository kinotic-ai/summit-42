package org.kinotic.summit42.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuilderApp;
import org.kinotic.summit42.api.model.Conversation;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * The app builder a signed-in user creates their organization's apps with, and chats with to have Claude build,
 * change and deploy each one. Every app is a regular Kinotic application of the organization, with one project
 * whose GitHub repository holds its code, and has a conversation of its own.
 */
@Publish
public interface AppBuilderService {

    /**
     * The zone Summit 42's services are published in, which the Kinotic servers list in
     * {@code kinotic.apiGateway.organizationServiceZones} so organizations' users reach them through the
     * management server.
     */
    String ZONE = "summit-42";

    /**
     * @return the organization's apps, ordered by name
     */
    Future<List<BuilderApp>> findApps();

    /**
     * Creates an app: a Kinotic application with the name, whose id is the slugified name, and its project, whose
     * GitHub repository is generated from Kinotic's app template. Requires the organization to have linked GitHub.
     *
     * @param name        the app's name; fails when the organization already has an application with that name
     * @param description one line on what the app is for, or null
     * @return the created app
     */
    Future<BuilderApp> createApp(String name, String description);

    /**
     * Sends the user's message about the app and streams Claude's turn: its reply as
     * {@link org.kinotic.summit42.api.model.BuildEventType#TEXT} chunks, interleaved with what it is doing to the
     * app. The stream completes when the turn is over, and ends with an
     * {@link org.kinotic.summit42.api.model.BuildEventType#ERROR} event when the turn fails or another turn of the
     * app is still running.
     *
     * @param applicationId the app's application id
     * @param message       what the user asked for
     * @return the turn's events
     */
    Flux<BuildEvent> chat(String applicationId, String message);

    /**
     * @param applicationId the app's application id
     * @return the app's conversation so far, empty when there is none
     */
    Future<Conversation> findConversation(String applicationId);

    /**
     * Clears the app's conversation, so the next message starts a new one. The app and its repository are kept.
     * Fails while a turn of the app is running.
     *
     * @param applicationId the app's application id
     */
    Future<Void> resetConversation(String applicationId);
}
