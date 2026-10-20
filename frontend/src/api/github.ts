import { Kinotic, type IKinotic, type IServiceProxy } from '@kinotic-ai/core'

const SERVICE_IDENTIFIER = 'management-api~org.kinotic.management.api.services.github.GitHubAppInstallationService'

/** The route of this UI GitHub's install returns to, through Kinotic, to finish linking the workspace. */
export const GITHUB_CALLBACK_PATH = '/github/callback'

/** What finishing a GitHub install returns, of what this UI reads: the URL the install was started to return to. */
export interface GitHubInstallCompletion {
    returnTo: string | null
}

/**
 * Links the workspace to GitHub through Kinotic's GitHub App, which holds the repositories of the apps Summit 42
 * builds. GitHub returns from the install to this UI's {@link GITHUB_CALLBACK_PATH}, which finishes the link.
 */
export class GitHubInstallationService {

    private readonly serviceProxy: IServiceProxy

    constructor(kinotic: IKinotic) {
        this.serviceProxy = kinotic.serviceProxy(SERVICE_IDENTIFIER)
    }

    /**
     * The GitHub install URL to send the browser to; once installed, the browser returns to the absolute URL
     * {@code returnTo} with GitHub's installation_id, state and code added to its query.
     */
    public startInstall(returnTo: string): Promise<string> {
        return this.serviceProxy.invoke('startInstall', [returnTo])
    }

    /** Finishes the link with the query parameters GitHub returned with. */
    public completeInstall(installationId: number, state: string, code: string): Promise<GitHubInstallCompletion> {
        return this.serviceProxy.invoke('completeInstall', [installationId, state, code])
    }

    /** Whether the workspace has linked GitHub. */
    public async isLinked(): Promise<boolean> {
        const installation = await this.serviceProxy.invoke('findForCurrentOrg')
        return installation != null
    }
}

export const GITHUB = new GitHubInstallationService(Kinotic)
