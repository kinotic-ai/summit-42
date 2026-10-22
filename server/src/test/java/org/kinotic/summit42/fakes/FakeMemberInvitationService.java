package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.EmailTemplate;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.domain.api.model.security.PendingInvite;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.management.api.services.security.MemberInvitationService;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the invitations it is asked to send. Like Kinotic's {@code MemberInvitationService}, it sends each in the
 * name of the organization participant the call is made as, and refuses any other caller.
 */
@RequiredArgsConstructor
public abstract class FakeMemberInvitationService implements MemberInvitationService {

    private final SecurityContext securityContext;
    private final List<SentInvitation> sent = new CopyOnWriteArrayList<>();

    @Override
    public Future<OrganizationMember> addMember(String email, String displayName, boolean sendInvitation,
                                                EmailTemplate emailTemplate) {
        record(email, displayName, null, emailTemplate);
        return Future.succeededFuture(new OrganizationMember("user-" + email, email, displayName, true, new Date(), true));
    }

    @Override
    public Future<Void> sendInvitation(String email, EmailTemplate emailTemplate) {
        record(email, null, null, emailTemplate);
        return Future.succeededFuture();
    }

    @Override
    public Future<PendingInvite> inviteMember(String email, String displayName, String applicationId,
                                              EmailTemplate emailTemplate) {
        record(email, displayName, applicationId, emailTemplate);
        return Future.succeededFuture(new PendingInvite().setEmail(email).setDisplayName(displayName).setApplicationId(applicationId));
    }

    /**
     * @return the invitations sent, oldest first
     */
    public List<SentInvitation> sent() {
        return List.copyOf(sent);
    }

    private void record(String email, String displayName, String applicationId, EmailTemplate emailTemplate) {
        OrganizationParticipant inviter = securityContext.requireParticipant(OrganizationParticipant.class);
        sent.add(new SentInvitation(inviter.getOrganizationId(), inviter.getId(), email, displayName, applicationId,
                                    emailTemplate));
    }
}
