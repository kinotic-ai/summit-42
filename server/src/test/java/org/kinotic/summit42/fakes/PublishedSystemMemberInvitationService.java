package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.model.security.Inviter;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.PendingInviteSummary;

/**
 * Publishes {@link FakeSystemMemberInvitationService} under the system server's {@code SystemMemberInvitationService},
 * with the functions Summit 42 calls.
 */
@Publish(namespace = "org.kinotic.system.api.services", name = "SystemMemberInvitationService")
@Zone(DomainUtil.SYSTEM_API_ZONE)
@Version("1.0.0")
public interface PublishedSystemMemberInvitationService {

    Future<OrganizationMember> addMember(Inviter inviter, String email, String displayName, String emailTemplateId);

    Future<Void> sendInvitation(Inviter inviter, String email, String emailTemplateId);

    Future<PendingInviteSummary> inviteMember(Inviter inviter, String email, String displayName, String applicationId,
                                              String emailTemplateId);
}
