import { Kinotic, Pageable } from '@kinotic-ai/core'
import { DeploymentStatusType, type Project } from '@kinotic-ai/management-api'
import { forgetApp } from '@/api/appBuilder'

/** The most projects an app's pages read; a Summit 42 app has one. */
const MAX_PROJECTS = 20

/** The projects of an application: the GitHub repositories its code lives in. */
export async function findProjects(applicationId: string): Promise<Project[]> {
    const page = await Kinotic.projects.findAllForApplication(applicationId, Pageable.create(0, MAX_PROJECTS))
    return page.content ?? []
}

/**
 * Deletes an app: its projects, which takes down what they deployed, and then its application. The GitHub
 * repositories are kept.
 */
export async function deleteApp(applicationId: string): Promise<void> {
    for (const project of await findProjects(applicationId)) {
        await Kinotic.projects.deleteByIdSync(project.id!)
    }
    await Kinotic.applications.deleteById(applicationId)
    forgetApp(applicationId)
}

/** How a deployment phase reads in the UI, and the kind that names its chip--* and dot--* colour. */
export function describePhase(phase: DeploymentStatusType | null | undefined): { text: string; kind: 'live' | 'working' | 'danger' | 'idle' } {
    let ret: { text: string; kind: 'live' | 'working' | 'danger' | 'idle' }
    switch (phase) {
        // a project's finished deployment is RUNNING, a microservice's DEPLOYED, and a UI's READY
        case DeploymentStatusType.RUNNING:
        case DeploymentStatusType.DEPLOYED:
        case DeploymentStatusType.READY:
            ret = { text: 'Live', kind: 'live' }
            break
        case DeploymentStatusType.FAILED:
            ret = { text: 'Failed', kind: 'danger' }
            break
        case DeploymentStatusType.ORPHANED:
            ret = { text: 'Removed', kind: 'idle' }
            break
        case null:
        case undefined:
            ret = { text: 'Not deployed', kind: 'idle' }
            break
        default:
            ret = { text: 'Deploying', kind: 'working' }
    }
    return ret
}

/** The GitHub page of a repository named owner/name. */
export function repoUrl(repoFullName: string): string {
    return `https://github.com/${repoFullName}`
}
