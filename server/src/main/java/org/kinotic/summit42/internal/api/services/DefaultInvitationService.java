package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.Inviter;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.management.api.model.PendingInviteSummary;
import org.kinotic.summit42.api.services.InvitationService;
import org.kinotic.summit42.internal.api.proxies.SystemMemberInvitationServiceProxy;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class DefaultInvitationService implements InvitationService {

    // Kinotic's migrations seed it for each environment that serves Summit 42, with this UI's link base
    private static final String INVITATION_EMAIL_TEMPLATE_ID = "summit-42-invitation";

    private final Vertx vertx;
    private final SecurityContext securityContext;
    private final SystemMemberInvitationServiceProxy systemInvitations;

    @Override
    public Future<OrganizationMember> addMember(String email, String displayName) {
        Inviter inviter = currentInviter();
        return callAsSummit42(() -> systemInvitations.addMember(inviter, email, displayName, INVITATION_EMAIL_TEMPLATE_ID));
    }

    @Override
    public Future<Void> sendInvitation(String email) {
        Inviter inviter = currentInviter();
        return callAsSummit42(() -> systemInvitations.sendInvitation(inviter, email, INVITATION_EMAIL_TEMPLATE_ID));
    }

    @Override
    public Future<PendingInviteSummary> inviteMember(String email, String displayName, String applicationId) {
        Inviter inviter = currentInviter();
        return callAsSummit42(() -> systemInvitations.inviteMember(inviter, email, displayName, applicationId,
                                                                   INVITATION_EMAIL_TEMPLATE_ID));
    }

    private Inviter currentInviter() {
        return Inviter.from(securityContext.requireParticipant(OrganizationParticipant.class));
    }

    // the system service takes the inviter on trust, so it serves only a cluster member calling with no participant
    private <T> Future<T> callAsSummit42(Supplier<Future<T>> call) {
        return Future.fromCompletionStage(securityContext.callWithoutParticipant(() -> call.get().toCompletionStage()),
                                          vertx.getOrCreateContext());
    }
}
