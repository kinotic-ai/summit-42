package org.kinotic.summit42.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.summit42.api.model.AppCreationEvent;
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
     * @return the organization's apps, ordered by name
     */
    Future<List<BuilderApp>> findApps();

    /**
     * Creates an app: a Kinotic application with the name, whose id is the slugified name, and its project, whose
     * GitHub repository is generated from Kinotic's app template. Requires the organization to have linked GitHub.
     * The stream reports each step as it starts, in order, and ends with a
     * {@link org.kinotic.summit42.api.model.AppCreationEventType#CREATED} event carrying the app; it fails with the
     * reason when the app cannot be created. The app is created to the end whether or not the stream is still
     * subscribed.
     *
     * The app takes its users' passwords from the start, so the people invited on its Users page can accept their
     * invitations.
     *
     * @param name        the app's name; fails when the organization already has an application with that name
     * @param description what the user wants the app to do, in their own words, or null; the app's description is
     *                    Claude's one-line summary of it, or null when it is null or cannot be summarized
     * @param openSignUp  whether anyone may sign up at the app, with an email and a password or through any sign-in
     *                    provider the app offers; false keeps the app to the people invited on its Users page
     * @return the steps of creating the app, then the created app
     */
    Flux<AppCreationEvent> createApp(String name, String description, boolean openSignUp);

    /**
     * Sends the user's message about the app and streams Claude's turn: its reply as
     * {@link org.kinotic.summit42.api.model.BuildEventType#TEXT} chunks, interleaved with what it is doing to the
     * app. The turn runs to its end whether or not the stream is still subscribed, and records its reply in the
     * conversation. The stream completes when the turn is over, and ends with an
     * {@link org.kinotic.summit42.api.model.BuildEventType#ERROR} event when the turn fails or another turn of the
     * app has stopped but not yet ended.
     * <p>
     * A message sent while a turn of the app is running steers that turn: Claude reads it after its current step,
     * the running turn streams it as a {@link org.kinotic.summit42.api.model.BuildEventType#USER_MESSAGE} event at
     * that point, and the stream this call returns completes at once without events. A message sent as the running
     * turn finishes, after Claude's last step, starts a turn of its own once that turn has ended.
     *
     * @param applicationId the app's application id
     * @param message       what the user asked for
     * @return the turn's events
     */
    Flux<BuildEvent> chat(String applicationId, String message);

    /**
     * Streams the app's running turn as {@link #chat} does, from the turn's first event: the events it has sent so
     * far, then the rest as they happen. Completes at once when no turn of the app is running, as when the turn
     * ended before the call, whose reply the conversation then holds.
     *
     * @param applicationId the app's application id
     * @return the running turn's events
     */
    Flux<BuildEvent> followRunningTurn(String applicationId);

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
