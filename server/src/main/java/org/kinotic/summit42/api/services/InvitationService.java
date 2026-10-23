package org.kinotic.summit42.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.domain.api.model.security.OrganizationMember;
import org.kinotic.management.api.model.PendingInviteSummary;

/**
 * The invitations a signed-in user sends into their organization and its apps. Each is sent in the user's name and
 * emailed with Summit 42's invitation template, whose link opens Summit 42.
 */
@Publish
public interface InvitationService {

    /**
     * Adds a person to the user's organization by email and emails them an invitation. Fails for an address that is
     * already a member or one an invitation is already pending for.
     *
     * @param email       the address the person signs in with
     * @param displayName the person's name, or null
     * @return the member as listed, pending until they first sign in; for an address that already has an account, a
     *         pending member with no id yet, listed once the invitation is accepted
     */
    Future<OrganizationMember> addMember(String email, String displayName);

    /**
     * Emails a pending member of the user's organization their invitation again, or a first one for a member added
     * without. Fails for an address that is not a pending member's.
     */
    Future<Void> sendInvitation(String email);

    /**
     * Invites someone into one of the organization's apps by email; accepting creates their account in the app.
     * Fails if the email already has an account in the app or an invitation is already pending for it.
     *
     * @param email         where to send the invitation
     * @param displayName   the invitee's name, or null
     * @param applicationId the app's Kinotic application
     * @return the pending invitation
     */
    Future<PendingInviteSummary> inviteMember(String email, String displayName, String applicationId);
}
