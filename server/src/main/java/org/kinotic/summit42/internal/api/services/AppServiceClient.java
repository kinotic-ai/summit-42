package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Timer;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.event.CRI;
import org.kinotic.core.api.event.Event;
import org.kinotic.core.api.event.EventBusService;
import org.kinotic.core.api.event.EventConstants;
import org.kinotic.core.api.event.EventConsumer;
import org.kinotic.core.api.event.Metadata;
import org.kinotic.core.api.security.Participant;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.utils.DomainUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Invokes a function of a service an application's microservice publishes, the way the application's own clients do,
 * with JSON arguments and a JSON result.
 */
@Component
@RequiredArgsConstructor
public class AppServiceClient {

    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(90);
    private static final String REPLY_RESOURCE = "summit42AppServiceClient";

    private final Vertx vertx;
    private final EventBusService eventBusService;

    /**
     * Calls the function as the participant, who must be a member of the application's organization.
     *
     * @param participant the participant the service sees as its caller
     * @param application the application whose microservice publishes the service
     * @param service     the service's name within the application's zone, its namespace and name, such as
     *                    {@code recipeapp.RecipeSearchService}
     * @param function    the function to call
     * @param arguments   the function's arguments as a JSON array, in order
     * @return the function's result as JSON, null when it returns nothing, or the first value of a function that
     * streams; fails with the service's error, when no microservice of the application publishes the service, or when
     * the function has not answered after 90 seconds
     */
    public Future<String> call(Participant participant, ApplicationKey application, String service, String function, String arguments) {
        // the service is addressed in the application's own zone, so a name that could carry another zone is refused
        if (!service.matches("[A-Za-z0-9.-]+") || !function.matches("[A-Za-z0-9_$]+")) {
            return Future.failedFuture(new IllegalStateException("The service is named by its namespace and name, such as "
                                                                + "recipeapp.RecipeSearchService, and the function by its name"));
        }
        String zone = DomainUtil.applicationZone(application.organizationId(), application.applicationId());
        CRI requestCri = CRI.create(EventConstants.SERVICE_DESTINATION_SCHEME, null, zone + "~" + service, "/" + function, null);
        CRI replyCri = CRI.create(EventConstants.REPLY_DESTINATION_SCHEME, UUID.randomUUID().toString(), REPLY_RESOURCE);
        String correlationId = UUID.randomUUID().toString();

        Promise<String> reply = Promise.promise();
        EventConsumer consumer = eventBusService.listen(replyCri);
        consumer.handler(event -> {
            String origin = event.metadata().get(EventConstants.ORIGIN_CRI_HEADER);
            if (origin != null) {
                // a stream's value names the service that streams it, which keeps streaming until cancelled
                Metadata cancel = Metadata.create();
                cancel.put(EventConstants.CONTROL_HEADER, EventConstants.CONTROL_VALUE_CANCEL);
                cancel.put(EventConstants.CORRELATION_ID_HEADER, correlationId);
                eventBusService.publish(Event.create(CRI.create(origin), cancel, null));
            }
            String error = event.metadata().get(EventConstants.ERROR_HEADER);
            if (error != null) {
                reply.tryFail(new IllegalStateException("The function failed: " + error));
            } else {
                reply.tryComplete(event.data() != null && event.data().length > 0 ? new String(event.data(), StandardCharsets.UTF_8) : null);
            }
        });
        Timer timeout = vertx.timer(REPLY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        timeout.onSuccess(fired -> reply.tryFail(new IllegalStateException(
                "The function did not answer within " + REPLY_TIMEOUT.toSeconds() + " seconds")));

        Metadata metadata = Metadata.create();
        metadata.put(EventConstants.REPLY_TO_HEADER, replyCri.raw());
        metadata.put(EventConstants.CORRELATION_ID_HEADER, correlationId);
        metadata.put(EventConstants.CONTENT_TYPE_HEADER, EventConstants.CONTENT_TYPE_JSON);
        Event<byte[]> request = Event.create(requestCri, metadata, arguments.getBytes(StandardCharsets.UTF_8), participant);
        return consumer.completion()
                       .compose(registered -> eventBusService.sendWithAck(request))
                       .recover(failure -> Future.failedFuture(
                               failure instanceof ReplyException replyException && replyException.failureType() == ReplyFailure.NO_HANDLERS
                                       ? new IllegalStateException("No microservice of the app publishes " + service
                                                                 + "; check that it is deployed and running")
                                       : failure))
                       .compose(nodeId -> reply.future())
                       .eventually(() -> {
                           timeout.cancel();
                           return consumer.unregister();
                       });
    }
}
