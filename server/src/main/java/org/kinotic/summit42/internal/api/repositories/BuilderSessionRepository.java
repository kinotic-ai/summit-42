package org.kinotic.summit42.internal.api.repositories;

import org.kinotic.domain.api.model.ApplicationKey;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds each app's {@link BuilderSession} in this server's memory, so a restart starts every conversation over; the
 * apps themselves live in Kinotic.
 */
@Component
public class BuilderSessionRepository {

    private final ConcurrentMap<ApplicationKey, BuilderSession> sessions = new ConcurrentHashMap<>();

    /**
     * @return the app's session, or null when it has none
     */
    public BuilderSession findById(ApplicationKey applicationKey) {
        return sessions.get(applicationKey);
    }

    /**
     * @return the app's session, created empty when it has none
     */
    public BuilderSession findOrCreate(ApplicationKey applicationKey) {
        return sessions.computeIfAbsent(applicationKey, BuilderSession::new);
    }
}
