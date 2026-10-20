package org.kinotic.summit42.internal.api.services;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One chat turn, as the app builder's tools see it: the signed-in user it acts for, their organization's session,
 * and where the tools report progress. The turn keeps its session's {@link BuilderSession#isTurnRunning()} set
 * until its stream has ended and the tool call running at that moment has returned, so no other turn starts while
 * this one may still commit.
 */
@Getter
@RequiredArgsConstructor
public class BuilderTurn {

    /**
     * The participant of the chat call, authenticated by the management gateway.
     */
    private final OrganizationParticipant participant;

    /**
     * The organization's session.
     */
    private final BuilderSession session;

    /**
     * Receives the progress events the turn streams.
     */
    private final Consumer<BuildEvent> events;

    // one hold for the turn's stream, plus one for each running tool call
    @Getter(AccessLevel.NONE)
    private final AtomicInteger holds = new AtomicInteger(1);

    /**
     * Holds the turn for a tool call, which releases it with {@link #release()} when it returns.
     *
     * @return false when the turn has ended, and the tool call must not run
     */
    public boolean hold() {
        return holds.getAndUpdate(count -> count == 0 ? 0 : count + 1) > 0;
    }

    /**
     * Releases the hold of the turn's stream or of a tool call; the last release ends the turn on its session.
     */
    public void release() {
        if (holds.decrementAndGet() == 0) {
            synchronized (session) {
                session.setTurnRunning(false);
            }
        }
    }
}
