package org.kinotic.summit42.fakes;

import org.kinotic.domain.api.model.security.Inviter;

/**
 * An invitation {@link FakeSystemMemberInvitationService} was asked to send.
 *
 * @param applicationId the app invited into, or null for the organization
 */
public record SentInvitation(Inviter inviter, String email, String displayName, String applicationId, String emailTemplateId) {
}
