import { Kinotic, Pageable } from '@kinotic-ai/core'
import { DeploymentStatusType, WatchEventType, WatchedType, type Project, type WatchEvent } from '@kinotic-ai/management-api'
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

/** One milestone of an app's deployments, as the UI words it for the people using the app. */
export interface DeploymentMilestone {
    text: string
    /** When it happened, as an ISO-8601 timestamp. */
    timestamp: string
}

/**
 * The milestones in a deployment history, newest first: each version the app started deploying, when it went live or
 * failed, and when its pages were published. The history's other entries record the platform's inner workings and are
 * left out.
 */
export function describeHistory(events: WatchEvent[]): DeploymentMilestone[] {
    const ret: DeploymentMilestone[] = []
    for (const event of events) {
        const text = milestoneOf(event)
        // each of a version's pages reports its own publishing
        if (text !== null && ret[ret.length - 1]?.text !== text) {
            ret.push({ text, timestamp: event['@timestamp'] })
        }
    }
    return ret
}

// the deployment and UI records' desired and observed values are DeploymentStates
function milestoneOf(event: WatchEvent): string | null {
    const state = event.value as { phase?: DeploymentStatusType; commitSha?: string | null } | null
    const version = state?.commitSha ? `version ${state.commitSha.slice(0, 7)}` : 'a new version'
    let ret: string | null = null
    if (event.type === WatchedType.PROJECT_DEPLOYMENT && event.eventType === WatchEventType.DESIRED_UPDATED) {
        ret = `Started deploying ${version}`
    } else if (event.type === WatchedType.PROJECT_DEPLOYMENT && event.eventType === WatchEventType.OBSERVED_REPORTED) {
        if (state?.phase === DeploymentStatusType.RUNNING) {
            ret = `Deployed ${version}`
        } else if (state?.phase === DeploymentStatusType.FAILED) {
            ret = 'A deployment failed'
        }
    } else if (event.type === WatchedType.UI_DEPLOYMENT && event.eventType === WatchEventType.OBSERVED_REPORTED
               && state?.phase === DeploymentStatusType.READY) {
        ret = `Published the pages of ${version}`
    }
    return ret
}

/** The GitHub page of a repository named owner/name. */
export function repoUrl(repoFullName: string): string {
    return `https://github.com/${repoFullName}`
}
