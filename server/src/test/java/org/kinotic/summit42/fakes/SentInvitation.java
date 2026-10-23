package org.kinotic.summit42.fakes;

import org.kinotic.domain.api.model.EmailTemplate;

/**
 * An invitation {@link FakeMemberInvitationService} was asked to send.
 *
 * @param organizationId the inviting user's organization
 * @param inviterId      the inviting user
 * @param applicationId  the app invited into, or null for the organization
 */
public record SentInvitation(String organizationId, String inviterId, String email, String displayName,
                             String applicationId, EmailTemplate emailTemplate) {
}
