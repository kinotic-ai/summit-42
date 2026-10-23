package org.kinotic.summit42.internal.api.services;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.management.api.model.RepositoryConnectionStatus;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.BuildEventType;
import org.kinotic.summit42.fakes.FakeAnthropic;
import org.kinotic.summit42.fakes.FakeGitHub;
import org.kinotic.summit42.fakes.FakeLlmConfigurationService;
import org.kinotic.summit42.fakes.FakeProjectService;
import org.kinotic.system.api.model.llm.LlmConfiguration;
import org.kinotic.system.api.model.llm.LlmProviderType;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

/**
 * Covers a chat turn while Kinotic's skills cannot be read from GitHub, in a context of its own, so no other test has
 * read the skills before.
 */
public class KinoticSkillsTests extends AppBuilderServiceTestBase {

    @Autowired
    private FakeProjectService projectService;
    @Autowired
    private FakeLlmConfigurationService llmConfigurationService;
    @Autowired
    private FakeAnthropic anthropic;
    @Autowired
    private FakeGitHub gitHub;
    @Autowired
    private Summit42Properties properties;

    @Test
    public void testChatAnswersWithAnErrorUntilTheSkillsCanBeRead() {
        OrganizationParticipant participant = newOrganizationParticipant();
        projectService.add(participant.getOrganizationId(), "todo", RepositoryConnectionStatus.CONNECTED);
        llmConfigurationService.save(new LlmConfiguration().setId("llm")
                                                           .setName(properties.getLlmConfigurationName())
                                                           .setProvider(LlmProviderType.ANTHROPIC)
                                                           .setModel("claude-test")
                                                           .setEndpoint(anthropic.baseUrl())
                                                           .setApiKeyHeld(true)
                                                           .setUpdated(new Date(0)),
                                     "test-key");
        anthropic.replyWithText("Your todo app is ready");

        gitHub.setFailing(true);
        List<BuildEvent> unavailable = chatAs(participant, "todo", "Build a todo app").collectList().block(TIMEOUT);
        gitHub.setFailing(false);
        List<BuildEvent> available = chatAs(participant, "todo", "Build a todo app").collectList().block(TIMEOUT);

        Assertions.assertEquals(List.of("Summit 42 could not load Kinotic's skills from GitHub. Please try again in a few"
                                        + " minutes."),
                                unavailable.stream().map(BuildEvent::getText).toList());
        Assertions.assertEquals(List.of("Your todo app is ready"),
                                available.stream().filter(event -> event.getType() == BuildEventType.TEXT)
                                         .map(BuildEvent::getText).toList());
        Assertions.assertEquals(1, anthropic.requests().size());
    }
}
