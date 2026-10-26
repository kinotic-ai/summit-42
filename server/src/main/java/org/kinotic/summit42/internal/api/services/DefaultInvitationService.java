package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.domain.api.model.security.identity.ApplicationParticipantIdentity;
import org.kinotic.domain.api.model.security.identity.OrganizationParticipantIdentity;
import org.kinotic.management.api.model.AddApplicationUserRequest;
import org.kinotic.management.api.services.security.MemberInvitationService;
import org.kinotic.summit42.api.services.InvitationService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultInvitationService implements InvitationService {

    private final MemberInvitationService memberInvitationService;
    private final Summit42EmailTemplates emailTemplates;

    @Override
    public Future<OrganizationParticipantIdentity> addOrganizationUser(String email, String displayName) {
        return memberInvitationService.addOrganizationUser(email, displayName, true, emailTemplates.getInvitation());
    }

    @Override
    public Future<ApplicationParticipantIdentity> addApplicationUser(String email, String displayName, String applicationId) {
        // the system gives each user of a Summit 42 app a tenant of their own, so none is named
        AddApplicationUserRequest request = new AddApplicationUserRequest().setEmail(email)
                                                                           .setDisplayName(displayName)
                                                                           .setApplicationId(applicationId)
                                                                           .setSendInvitation(true);
        return memberInvitationService.addApplicationUser(request, emailTemplates.getInvitation());
    }

    @Override
    public Future<Void> sendInvitation(String email, String applicationId) {
        return memberInvitationService.sendInvitation(email, applicationId, emailTemplates.getInvitation());
    }
}
