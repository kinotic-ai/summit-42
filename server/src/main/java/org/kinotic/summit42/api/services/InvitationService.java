package org.kinotic.summit42.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.domain.api.model.security.identity.ApplicationParticipantIdentity;
import org.kinotic.domain.api.model.security.identity.OrganizationParticipantIdentity;

/**
 * The people a signed-in user adds to their organization and its apps, each emailed an invitation in the user's name
 * with Summit 42's invitation template, whose link opens Summit 42.
 */
@Publish
public interface InvitationService {

    /**
     * Adds a person to the user's organization by email and emails them an invitation, which they accept with a
     * password while the organization takes one, or by signing in through one of its providers under the same
     * address. Fails for an address that is already a member.
     *
     * @param email       the address the person signs in with
     * @param displayName the person's name, or null
     * @return the member as listed, pending until they first sign in
     */
    Future<OrganizationParticipantIdentity> addOrganizationUser(String email, String displayName);

    /**
     * Adds a person to one of the organization's apps by email and emails them an invitation, which they accept with
     * a password, or by signing in to the app through one of its providers under the same address. Fails for an
     * address that already has a user in the app, and for an app whose login offers no way to accept the invitation.
     *
     * @param email         the address the person signs in with
     * @param displayName   the person's name, or null
     * @param applicationId the app's Kinotic application
     * @return the user as listed, pending until they first sign in
     */
    Future<ApplicationParticipantIdentity> addApplicationUser(String email, String displayName, String applicationId);

    /**
     * Emails a pending member of the user's organization, or a pending user of one of its apps, their invitation
     * again. Fails for an address that is not a pending member's or user's.
     *
     * @param email         the pending member's or user's address
     * @param applicationId the app whose user is invited, or null for a member of the organization
     */
    Future<Void> sendInvitation(String email, String applicationId);
}
