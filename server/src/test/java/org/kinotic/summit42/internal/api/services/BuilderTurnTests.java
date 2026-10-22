package org.kinotic.summit42.internal.api.services;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.model.security.participant.DefaultOrganizationParticipant;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;

public class BuilderTurnTests {

    @Test
    public void testReleasingTheStreamEndsATurnWithNoToolRunning() {
        BuilderSession session = runningSession();
        BuilderTurn turn = turnOf(session);

        turn.release();

        Assertions.assertFalse(session.isTurnRunning());
    }

    @Test
    public void testTheTurnEndsWhenTheLastRunningToolReturns() {
        BuilderSession session = runningSession();
        BuilderTurn turn = turnOf(session);

        Assertions.assertTrue(turn.hold());
        Assertions.assertTrue(turn.hold());
        turn.release();
        turn.release();
        boolean runningWithOneToolLeft = session.isTurnRunning();
        turn.release();

        Assertions.assertTrue(runningWithOneToolLeft);
        Assertions.assertFalse(session.isTurnRunning());
    }

    @Test
    public void testAToolCannotHoldATurnThatHasEnded() {
        BuilderSession session = runningSession();
        BuilderTurn turn = turnOf(session);
        turn.release();

        Assertions.assertFalse(turn.hold());
        Assertions.assertFalse(session.isTurnRunning());
    }

    private static BuilderSession runningSession() {
        return new BuilderSession(new ApplicationKey("org", "app"), new Conversation()).setTurnRunning(true);
    }

    private static BuilderTurn turnOf(BuilderSession session) {
        return new BuilderTurn(new DefaultOrganizationParticipant("user", "org", null, null), session, "project", event -> {});
    }
}
