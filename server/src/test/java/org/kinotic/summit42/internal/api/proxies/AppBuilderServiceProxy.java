package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuilderApp;
import org.kinotic.summit42.api.model.Conversation;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Summit 42's {@code AppBuilderService}, called over Kinotic RPC the way the management gateway calls it for the
 * signed-in user.
 */
@Proxy(namespace = "org.kinotic.summit42.api.services", name = "AppBuilderService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface AppBuilderServiceProxy {

    Future<List<BuilderApp>> findApps();

    Future<BuilderApp> createApp(String name, String description);

    Flux<BuildEvent> chat(String applicationId, String message);

    Flux<BuildEvent> followRunningTurn(String applicationId);

    Future<Conversation> findConversation(String applicationId);

    Future<Void> resetConversation(String applicationId);
}
