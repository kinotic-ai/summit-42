package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.model.security.Inviter;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.PendingInviteSummary;

/**
 * Invitations sent on behalf of an organization's user with an email template Summit 42 names, from the system
 * server's {@code SystemMemberInvitationService}. The service refuses a call made for a participant, so Summit 42 calls
 * it as itself.
 */
@Proxy(namespace = "org.kinotic.system.api.services", name = "SystemMemberInvitationService")
@Zone(DomainUtil.SYSTEM_API_ZONE)
@Version("1.0.0")
public interface SystemMemberInvitationServiceProxy {

    Future<OrganizationMember> addMember(Inviter inviter, String email, String displayName, String emailTemplateId);

    Future<Void> sendInvitation(Inviter inviter, String email, String emailTemplateId);

    Future<PendingInviteSummary> inviteMember(Inviter inviter, String email, String displayName, String applicationId,
                                              String emailTemplateId);
}
