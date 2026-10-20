import { Kinotic, type IKinotic, type IServiceProxy } from '@kinotic-ai/core'

const SERVICE_IDENTIFIER = 'management-api~org.kinotic.management.api.services.github.GitHubAppInstallationService'

/**
 * Links the workspace to GitHub through Kinotic's GitHub App, which holds the repositories of the apps Summit 42
 * builds. GitHub returns from the install to the Kinotic portal, which finishes the link and sends the browser back.
 */
export class GitHubInstallationService {

    private readonly serviceProxy: IServiceProxy

    constructor(kinotic: IKinotic) {
        this.serviceProxy = kinotic.serviceProxy(SERVICE_IDENTIFIER)
    }

    /** The GitHub install URL to send the browser to; once linked, the browser returns to {@code returnTo}. */
    public startInstall(returnTo: string): Promise<string> {
        return this.serviceProxy.invoke('startInstall', [returnTo])
    }

    /** Whether the workspace has linked GitHub. */
    public async isLinked(): Promise<boolean> {
        const installation = await this.serviceProxy.invoke('findForCurrentOrg')
        return installation != null
    }
}

export const GITHUB = new GitHubInstallationService(Kinotic)
