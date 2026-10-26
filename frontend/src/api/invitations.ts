import { Kinotic, type IKinotic, type IServiceProxy } from '@kinotic-ai/core'
import type { ApplicationParticipantIdentity, OrganizationParticipantIdentity } from '@kinotic-ai/management-api'

const SERVICE_IDENTIFIER = 'management-api~org.kinotic.summit42.api.services.InvitationService'

/**
 * The people the signed-in user adds to their organization and its apps, each emailed an invitation in the user's
 * name whose link opens this UI.
 */
export class InvitationService {

    private readonly serviceProxy: IServiceProxy

    constructor(kinotic: IKinotic) {
        this.serviceProxy = kinotic.serviceProxy(SERVICE_IDENTIFIER)
    }

    /** Adds a person to the organization and emails them an invitation; they are listed as pending until they join. */
    public addOrganizationUser(email: string, displayName: string | null): Promise<OrganizationParticipantIdentity> {
        return this.serviceProxy.invoke('addOrganizationUser', [email, displayName])
    }

    /** Adds a person to one of the organization's apps and emails them an invitation; they are pending until they first sign in. */
    public addApplicationUser(email: string, displayName: string | null, applicationId: string): Promise<ApplicationParticipantIdentity> {
        return this.serviceProxy.invoke('addApplicationUser', [email, displayName, applicationId])
    }

    /** Emails a pending member of the organization, or a pending user of the app, their invitation again. */
    public sendInvitation(email: string, applicationId: string | null): Promise<void> {
        return this.serviceProxy.invoke('sendInvitation', [email, applicationId])
    }
}

export const INVITATIONS = new InvitationService(Kinotic)
