package org.kinotic.summit42.internal.api.repositories;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds each organization's {@link BuilderSession} in this server's memory, so a restart starts every conversation
 * over; the apps themselves live in Kinotic.
 */
@Component
public class BuilderSessionRepository {

    private final ConcurrentMap<String, BuilderSession> sessions = new ConcurrentHashMap<>();

    /**
     * @return the organization's session, created empty when it has none
     */
    public BuilderSession findOrCreate(String organizationId) {
        return sessions.computeIfAbsent(organizationId, BuilderSession::new);
    }
}
