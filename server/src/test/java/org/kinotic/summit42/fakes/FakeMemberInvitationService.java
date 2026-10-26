package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.EmailTemplate;
import org.kinotic.domain.api.model.security.identity.ApplicationParticipantIdentity;
import org.kinotic.domain.api.model.security.identity.OrganizationParticipantIdentity;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.management.api.model.AddApplicationUserRequest;
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
    public Future<OrganizationParticipantIdentity> addOrganizationUser(String email, String displayName, boolean sendInvitation,
                                                                       EmailTemplate emailTemplate) {
        OrganizationParticipant inviter = record(email, displayName, null, emailTemplate);
        OrganizationParticipantIdentity ret = new OrganizationParticipantIdentity();
        ret.setOrganizationId(inviter.getOrganizationId());
        ret.setEmail(email).setPending(true);
        ret.setId("user-" + email).setDisplayName(displayName).setEnabled(true).setCreated(new Date());
        return Future.succeededFuture(ret);
    }

    @Override
    public Future<ApplicationParticipantIdentity> addApplicationUser(AddApplicationUserRequest request, EmailTemplate emailTemplate) {
        OrganizationParticipant inviter = record(request.getEmail(), request.getDisplayName(), request.getApplicationId(), emailTemplate);
        ApplicationParticipantIdentity ret = new ApplicationParticipantIdentity();
        ret.setOrganizationId(inviter.getOrganizationId()).setApplicationId(request.getApplicationId()).setTenantId(request.getTenantId());
        ret.setEmail(request.getEmail()).setPending(true);
        ret.setId("user-" + request.getEmail()).setDisplayName(request.getDisplayName()).setEnabled(true).setCreated(new Date());
        return Future.succeededFuture(ret);
    }

    @Override
    public Future<Void> sendInvitation(String email, String applicationId, EmailTemplate emailTemplate) {
        record(email, null, applicationId, emailTemplate);
        return Future.succeededFuture();
    }

    /**
     * @return the invitations sent, oldest first
     */
    public List<SentInvitation> sent() {
        return List.copyOf(sent);
    }

    // records the invitation, sent by the organization participant of the call
    private OrganizationParticipant record(String email, String displayName, String applicationId, EmailTemplate emailTemplate) {
        OrganizationParticipant inviter = securityContext.requireParticipant(OrganizationParticipant.class);
        sent.add(new SentInvitation(inviter.getOrganizationId(), inviter.getId(), email, displayName, applicationId,
                                    emailTemplate));
        return inviter;
    }
}
