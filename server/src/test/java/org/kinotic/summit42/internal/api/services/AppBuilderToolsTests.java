package org.kinotic.summit42.internal.api.services;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.domain.api.model.security.participant.DefaultOrganizationParticipant;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.TreeMap;

/**
 * Calls the app builder's tools the way Spring AI calls them during a chat turn, for the outcomes they reach before
 * calling Kinotic, GitHub or Kinotic's documentation. The context is closed after the class, since the JVM runs one
 * Ignite node at a time.
 */
@SpringBootTest
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class AppBuilderToolsTests {

    @Autowired
    private AppBuilderTools tools;

    @Test
    public void testAToolIsRefusedOnceItsTurnHasEnded() {
        BuilderTurn turn = newTurn();
        turn.release();

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                                                          () -> tools.listFiles(contextOf(turn)));
        Assertions.assertEquals("The turn has ended", e.getMessage());
    }

    @Test
    public void testAFailedToolCallStillLetsItsTurnEnd() {
        BuilderTurn turn = newTurn();

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                                                          () -> tools.waitForDeployment(contextOf(turn)));
        turn.release();

        Assertions.assertEquals("Nothing has been committed yet", e.getMessage());
        Assertions.assertFalse(turn.getSession().isTurnRunning());
    }

    @Test
    public void testReadKinoticDocsListsThePagesForAnUnknownPage() {
        String answer = tools.readKinoticDocs("05.persistence/99.no-such-page.md");

        Assertions.assertTrue(answer.startsWith("Unknown page. The pages are:"), answer);
        Assertions.assertTrue(answer.contains("\n05.persistence/02.defining-entities.md\n"), answer);
    }

    private static BuilderTurn newTurn() {
        BuilderSession session = new BuilderSession(new ApplicationKey("org", "app")).setTurnRunning(true);
        return new BuilderTurn(new DefaultOrganizationParticipant("user", "org", null, null), session, "project",
                               new SkillSet("commit", new TreeMap<>()), event -> {});
    }

    private static ToolContext contextOf(BuilderTurn turn) {
        return new ToolContext(Map.of(AppBuilderTools.TURN, turn));
    }
}
