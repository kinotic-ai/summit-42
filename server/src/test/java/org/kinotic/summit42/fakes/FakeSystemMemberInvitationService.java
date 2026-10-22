package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AuthorizationException;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.Inviter;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.management.api.model.PendingInviteSummary;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the invitations it is asked to send. Like the real service, it refuses a call made for a participant.
 */
@RequiredArgsConstructor
public class FakeSystemMemberInvitationService implements PublishedSystemMemberInvitationService {

    private final SecurityContext securityContext;
    private final List<SentInvitation> sent = new CopyOnWriteArrayList<>();

    @Override
    public Future<OrganizationMember> addMember(Inviter inviter, String email, String displayName, String emailTemplateId) {
        record(new SentInvitation(inviter, email, displayName, null, emailTemplateId));
        return Future.succeededFuture(new OrganizationMember("user-" + email, email, displayName, true, new Date(), true));
    }

    @Override
    public Future<Void> sendInvitation(Inviter inviter, String email, String emailTemplateId) {
        record(new SentInvitation(inviter, email, null, null, emailTemplateId));
        return Future.succeededFuture();
    }

    @Override
    public Future<PendingInviteSummary> inviteMember(Inviter inviter, String email, String displayName, String applicationId,
                                                     String emailTemplateId) {
        record(new SentInvitation(inviter, email, displayName, applicationId, emailTemplateId));
        return Future.succeededFuture(new PendingInviteSummary().setEmail(email).setDisplayName(displayName));
    }

    /**
     * @return the invitations sent, oldest first
     */
    public List<SentInvitation> sent() {
        return List.copyOf(sent);
    }

    private void record(SentInvitation invitation) {
        if (securityContext.currentParticipant() != null) {
            throw new AuthorizationException("Access denied");
        }
        sent.add(invitation);
    }
}
