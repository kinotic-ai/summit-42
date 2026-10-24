import { Kinotic, type IKinotic, type IServiceProxy } from '@kinotic-ai/core'
import type { Observable } from 'rxjs'
import { reactive } from 'vue'
import { SESSION } from '@/api/session'

/** What one event of a chat turn carries. */
export type BuildEventType = 'TEXT' | 'PROGRESS' | 'APP_READY' | 'USER_MESSAGE' | 'ERROR'

/**
 * What a PROGRESS step does: reading the app's files, a skill or the docs; committing files; deploying; or checking how
 * the deployed app behaves, by reading its logs or calling its services.
 */
export type BuildStepType = 'READING' | 'COMMITTING' | 'DEPLOYING' | 'CHECKING'

/**
 * One event of a chat turn: TEXT is a chunk of the assistant's reply to append, PROGRESS a one-line status of what
 * the builder is doing, APP_READY carries the deployed app's url, USER_MESSAGE a message the user sent during the
 * turn, which Claude reads from that point on, and ERROR the text of what failed, after which the stream ends.
 */
export interface BuildEvent {
    type: BuildEventType
    text: string | null
    url: string | null
    /** What the step does, for PROGRESS. */
    stepType: BuildStepType | null
    /** When the event happened, in epoch milliseconds. */
    timestamp: number
}

/** The stages a turn that builds an app moves through, in order. */
export const BUILD_STAGES = ['PLANNING', 'WRITING', 'DEPLOYING', 'LIVE'] as const

export type BuildStage = typeof BUILD_STAGES[number]

/** Where a running turn is: its stage, a line saying what it is doing, and when it started in epoch milliseconds. */
export interface BuildProgress {
    stage: BuildStage
    detail: string
    startedAt: number
}

export type ChatRole = 'USER' | 'ASSISTANT'

export interface ChatMessage {
    role: ChatRole
    text: string
}

/** An app of the organization: a Kinotic application the user named, and the url of its UI once deployed. */
export interface BuilderApp {
    applicationId: string
    name: string
    description: string | null
    url: string | null
}

/** The chat with the builder about one app. */
export interface Conversation {
    messages: ChatMessage[]
}

const SERVICE_IDENTIFIER = 'management-api~org.kinotic.summit42.api.services.AppBuilderService'

/** The Summit 42 app builder the signed-in organization creates its apps with and chats with about each one. */
export class AppBuilderService {

    private readonly serviceProxy: IServiceProxy

    constructor(kinotic: IKinotic) {
        this.serviceProxy = kinotic.serviceProxy(SERVICE_IDENTIFIER)
    }

    /** The organization's apps, ordered by name. */
    public findApps(): Promise<BuilderApp[]> {
        return this.serviceProxy.invoke('findApps')
    }

    /** Creates an app with its GitHub repository; rejects when the organization already has an app with that name. */
    public createApp(name: string, description: string | null): Promise<BuilderApp> {
        return this.serviceProxy.invoke('createApp', [name, description])
    }

    /**
     * Sends a message about the app; the events of the builder's turn follow, and the stream completes when the turn
     * is over. A message sent while a turn runs steers it: the running turn streams it as a USER_MESSAGE event where
     * Claude reads it, and this stream completes without events; one sent after the turn's last step streams a turn
     * of its own, which starts once the running turn has ended.
     */
    public chat(applicationId: string, message: string): Observable<BuildEvent> {
        return this.serviceProxy.invokeStream('chat', [applicationId, message])
    }

    /**
     * Follows the app's running turn as chat streams it, from its first event; completes at once when no turn runs.
     * A turn runs to its end whether or not anyone follows it.
     */
    public followRunningTurn(applicationId: string): Observable<BuildEvent> {
        return this.serviceProxy.invokeStream('followRunningTurn', [applicationId])
    }

    /** The app's conversation so far, to render on load. */
    public findConversation(applicationId: string): Promise<Conversation> {
        return this.serviceProxy.invoke('findConversation', [applicationId])
    }

    /** Starts the app's conversation over. */
    public resetConversation(applicationId: string): Promise<void> {
        return this.serviceProxy.invoke('resetConversation', [applicationId])
    }
}

export const APP_BUILDER = new AppBuilderService(Kinotic)

/** The organization's apps every page reads, ordered by name; {@link loadApps} fills it. */
export const APPS = reactive({
    list: [] as BuilderApp[],
    /** The organization the list was loaded for, or null before the first load. */
    organizationId: null as string | null,
})

/** Loads the signed-in organization's apps into {@link APPS}. */
export async function loadApps(): Promise<void> {
    APPS.list = await APP_BUILDER.findApps()
    APPS.organizationId = SESSION.organizationId
}

/** Loads the signed-in organization's apps into {@link APPS} unless they are loaded already. */
export async function ensureAppsLoaded(): Promise<void> {
    if (APPS.organizationId === null || APPS.organizationId !== SESSION.organizationId) {
        await loadApps()
    }
}

// the descriptions of apps created here whose chats have not sent them yet, keyed by application id
const unsentDescriptions = new Map<string, string>()

/** Creates an app and adds it to {@link APPS}; its description becomes the first message of its chat. */
export async function createApp(name: string, description: string | null): Promise<BuilderApp> {
    const app = await APP_BUILDER.createApp(name, description)
    if (description) {
        unsentDescriptions.set(app.applicationId, description)
    }
    APPS.list = [...APPS.list, app].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
    return APPS.list.find(listed => listed.applicationId === app.applicationId) ?? app
}

/** The description of an app created by {@link createApp} the first time it is asked for, then null. */
export function takeUnsentDescription(applicationId: string): string | null {
    const ret = unsentDescriptions.get(applicationId) ?? null
    unsentDescriptions.delete(applicationId)
    return ret
}

/** Records that the app was deployed and its UI serves at the url. */
export function markDeployed(applicationId: string, url: string): void {
    const app = APPS.list.find(listed => listed.applicationId === applicationId)
    if (app) {
        app.url = url
    }
}

/** Removes a deleted app from {@link APPS}. */
export function forgetApp(applicationId: string): void {
    APPS.list = APPS.list.filter(listed => listed.applicationId !== applicationId)
}

/** Records an app's new name and description. */
export function markRenamed(applicationId: string, name: string, description: string | null): void {
    const app = APPS.list.find(listed => listed.applicationId === applicationId)
    if (app) {
        app.name = name
        app.description = description
    }
}
