package org.kinotic.summit42.internal.api.repositories;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.summit42.api.model.Conversation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds each app's {@link BuilderSession} in this server's memory, with the conversation the
 * {@link ConversationRepository} saved for the app, and saves the conversation there as it changes.
 */
@Component
@RequiredArgsConstructor
public class BuilderSessionRepository {

    private final ConversationRepository conversationRepository;
    private final ConcurrentMap<ApplicationKey, Future<BuilderSession>> sessions = new ConcurrentHashMap<>();

    /**
     * @return the app's session, holding its saved conversation, or an empty one when none was saved
     */
    public Future<BuilderSession> findById(ApplicationKey applicationKey) {
        Future<BuilderSession> ret = sessions.computeIfAbsent(applicationKey, key ->
                conversationRepository.findById(key)
                                      .map(saved -> new BuilderSession(key, saved != null ? saved : new Conversation())));
        // a failed load is not kept, so the next call loads the conversation again
        ret.onFailure(e -> sessions.remove(applicationKey, ret));
        return ret;
    }

    /**
     * Forgets the app's session, marking it {@link BuilderSession#isDeleted() deleted}, and deletes its saved
     * conversation.
     */
    public Future<Void> deleteById(ApplicationKey applicationKey) {
        Future<BuilderSession> removed = sessions.remove(applicationKey);
        Future<Void> saved = Future.succeededFuture();
        if (removed != null) {
            // the delete runs after the session's last save, which would otherwise write the conversation back
            saved = removed.compose(session -> {
                synchronized (session) {
                    session.setDeleted(true);
                    return session.getLastSave();
                }
            });
        }
        return saved.transform(v -> conversationRepository.deleteById(applicationKey));
    }

    /**
     * Saves the session's conversation as it is now, once every earlier save of the session has completed, unless the
     * session is {@link BuilderSession#isDeleted() deleted}.
     */
    public Future<Void> save(BuilderSession session) {
        Future<Void> ret;
        synchronized (session) {
            if (session.isDeleted()) {
                ret = Future.succeededFuture();
            } else {
                Conversation snapshot = new Conversation().setMessages(new ArrayList<>(session.getConversation().getMessages()));
                ret = session.getLastSave()
                             .transform(previous -> conversationRepository.save(session.getApplicationKey(), snapshot));
                session.setLastSave(ret);
            }
        }
        return ret;
    }
}
