package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.core.api.exceptions.AuthorizationException;
import org.kinotic.core.api.security.Participant;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.identity.ApplicationParticipantIdentity;
import org.kinotic.domain.api.model.security.identity.OrganizationParticipantIdentity;
import org.kinotic.domain.api.model.security.participant.DefaultOrganizationParticipant;
import org.kinotic.domain.api.model.security.participant.DefaultSystemParticipant;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.fakes.FakeKinoticConfiguration;
import org.kinotic.summit42.fakes.FakeMemberInvitationService;
import org.kinotic.summit42.fakes.SentInvitation;
import org.kinotic.summit42.internal.api.proxies.InvitationServiceProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Calls Summit 42's {@code InvitationService} over Kinotic RPC as the management gateway would for a signed-in user,
 * against a fake of Kinotic's {@code MemberInvitationService}, which refuses a caller that is not an organization
 * participant as the real one does.
 */
@SpringBootTest
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@Import(FakeKinoticConfiguration.class)
public class InvitationServiceTests {

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") // Kinotic wires the proxy
    @Autowired
    private InvitationServiceProxy invitationService;
    @Autowired
    private SecurityContext securityContext;
    @Autowired
    private FakeMemberInvitationService memberInvitations;
    @Autowired
    private Summit42EmailTemplates emailTemplates;

    @Test
    public void testInvitationsAreSentInTheUsersNameWithSummit42sTemplate() throws Exception {
        String organizationId = "org-" + UUID.randomUUID();
        OrganizationParticipant user = DefaultOrganizationParticipant.builder()
                                                                     .id("ivy@" + organizationId)
                                                                     .organizationId(organizationId)
                                                                     .metadata(Map.of("displayName", "Ivy Inviter"))
                                                                     .roles(List.of())
                                                                     .build();

        OrganizationParticipantIdentity member = await(callAs(user, service -> service.addOrganizationUser("pat@example.test", "Pat")));
        Assertions.assertEquals("pat@example.test", member.getEmail());
        Assertions.assertTrue(member.isPending());
        await(callAs(user, service -> service.sendInvitation("pat@example.test", null)));
        ApplicationParticipantIdentity appUser = await(callAs(user, service -> service.addApplicationUser("sam@example.test", "Sam", "todo")));
        Assertions.assertEquals("sam@example.test", appUser.getEmail());
        Assertions.assertEquals("todo", appUser.getApplicationId());
        await(callAs(user, service -> service.sendInvitation("sam@example.test", "todo")));

        List<SentInvitation> sent = memberInvitations.sent().stream()
                                                     .filter(invitation -> invitation.organizationId().equals(organizationId))
                                                     .toList();
        Assertions.assertEquals(List.of(sentBy(user, "pat@example.test", "Pat", null),
                                        sentBy(user, "pat@example.test", null, null),
                                        sentBy(user, "sam@example.test", "Sam", "todo"),
                                        sentBy(user, "sam@example.test", null, "todo")),
                                sent);
        Assertions.assertEquals("https://summit42.example.test", emailTemplates.getInvitation().getUiBaseUrl());
    }

    @Test
    public void testACallerThatIsNotAnOrganizationParticipantIsRefused() {
        Participant system = DefaultSystemParticipant.builder()
                                                     .id("system-" + UUID.randomUUID())
                                                     .metadata(Map.of())
                                                     .roles(List.of())
                                                     .build();

        ExecutionException e = Assertions.assertThrows(ExecutionException.class,
                                                       () -> await(callAs(system, service -> service.addOrganizationUser("pat@example.test", null))));
        Assertions.assertInstanceOf(AuthorizationException.class, e.getCause());
    }

    private SentInvitation sentBy(OrganizationParticipant user, String email, String displayName, String applicationId) {
        return new SentInvitation(user.getOrganizationId(), user.getId(), email, displayName, applicationId,
                                  emailTemplates.getInvitation());
    }

    // the proxy sends a call as the participant bound to the context it is invoked on
    private <T> CompletableFuture<T> callAs(Participant participant, Function<InvitationServiceProxy, Future<T>> call) {
        return securityContext.callAs(participant, () -> call.apply(invitationService).toCompletionStage());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }
}
