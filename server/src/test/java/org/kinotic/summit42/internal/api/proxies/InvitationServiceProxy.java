package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.PendingInviteSummary;

/**
 * Summit 42's {@code InvitationService}, called over Kinotic RPC the way the management gateway calls it for the
 * signed-in user.
 */
@Proxy(namespace = "org.kinotic.summit42.api.services", name = "InvitationService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface InvitationServiceProxy {

    Future<OrganizationMember> addMember(String email, String displayName);

    Future<Void> sendInvitation(String email);

    Future<PendingInviteSummary> inviteMember(String email, String displayName, String applicationId);
}
