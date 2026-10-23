import { Kinotic, type IKinotic, type IServiceProxy } from '@kinotic-ai/core'
import type { OrganizationMember, PendingInviteSummary } from '@kinotic-ai/management-api'

const SERVICE_IDENTIFIER = 'management-api~org.kinotic.summit42.api.services.InvitationService'

/**
 * The invitations the signed-in user sends into their organization and its apps, in their name, with emails whose
 * links open this UI.
 */
export class InvitationService {

    private readonly serviceProxy: IServiceProxy

    constructor(kinotic: IKinotic) {
        this.serviceProxy = kinotic.serviceProxy(SERVICE_IDENTIFIER)
    }

    /** Adds a person to the organization and emails them an invitation; they are listed as pending until they join. */
    public addMember(email: string, displayName: string | null): Promise<OrganizationMember> {
        return this.serviceProxy.invoke('addMember', [email, displayName])
    }

    /** Emails a pending member their invitation again, or a first one for a member added without. */
    public sendInvitation(email: string): Promise<void> {
        return this.serviceProxy.invoke('sendInvitation', [email])
    }

    /** Invites someone into one of the organization's apps by email. */
    public inviteMember(email: string, displayName: string | null, applicationId: string): Promise<PendingInviteSummary> {
        return this.serviceProxy.invoke('inviteMember', [email, displayName, applicationId])
    }
}

export const INVITATIONS = new InvitationService(Kinotic)
