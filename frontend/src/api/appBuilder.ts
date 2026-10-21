import { Kinotic, type IKinotic, type IServiceProxy } from '@kinotic-ai/core'
import type { Observable } from 'rxjs'

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

/** An app of the workspace: a Kinotic application the user named, and the url of its UI once deployed. */
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

/** The Summit 42 app builder the signed-in workspace creates its apps with and chats with about each one. */
export class AppBuilderService {

    private readonly serviceProxy: IServiceProxy

    constructor(kinotic: IKinotic) {
        this.serviceProxy = kinotic.serviceProxy(SERVICE_IDENTIFIER)
    }

    /** The workspace's apps, ordered by name. */
    public findApps(): Promise<BuilderApp[]> {
        return this.serviceProxy.invoke('findApps')
    }

    /** Creates an app with its GitHub repository; rejects when the workspace already has an app with that name. */
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
