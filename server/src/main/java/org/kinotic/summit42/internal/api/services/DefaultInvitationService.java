package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.domain.api.services.security.MemberInvitationService;
import org.kinotic.management.api.model.PendingInviteSummary;
import org.kinotic.summit42.api.services.InvitationService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultInvitationService implements InvitationService {

    private final MemberInvitationService memberInvitationService;
    private final Summit42EmailTemplates emailTemplates;

    @Override
    public Future<OrganizationMember> addMember(String email, String displayName) {
        return memberInvitationService.addMember(email, displayName, true, emailTemplates.getInvitation());
    }

    @Override
    public Future<Void> sendInvitation(String email) {
        return memberInvitationService.sendInvitation(email, emailTemplates.getInvitation());
    }

    @Override
    public Future<PendingInviteSummary> inviteMember(String email, String displayName, String applicationId) {
        return memberInvitationService.inviteMember(email, displayName, applicationId, emailTemplates.getInvitation())
                                      .map(PendingInviteSummary::from);
    }
}
