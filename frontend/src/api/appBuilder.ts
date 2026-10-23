import { Kinotic, type IKinotic, type IServiceProxy } from '@kinotic-ai/core'
import type { Observable } from 'rxjs'
import { reactive } from 'vue'
import { SESSION } from '@/api/session'

/** What one event of a chat turn carries. */
export type BuildEventType = 'TEXT' | 'PROGRESS' | 'APP_READY' | 'ERROR'

/**
 * One event of a chat turn: TEXT is a chunk of the assistant's reply to append, PROGRESS a one-line status of what
 * the builder is doing, APP_READY carries the deployed app's url, and ERROR the text of what failed, after which
 * the stream ends.
 */
export interface BuildEvent {
    type: BuildEventType
    text: string | null
    url: string | null
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
     * is over.
     */
    public chat(applicationId: string, message: string): Observable<BuildEvent> {
        return this.serviceProxy.invokeStream('chat', [applicationId, message])
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

/** Creates an app and adds it to {@link APPS}. */
export async function createApp(name: string, description: string | null): Promise<BuilderApp> {
    const app = await APP_BUILDER.createApp(name, description)
    APPS.list = [...APPS.list, app].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
    return APPS.list.find(listed => listed.applicationId === app.applicationId) ?? app
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
