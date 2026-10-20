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

/** The workspace's conversation with the builder, and the url of its deployed app once there is one. */
export interface Conversation {
    messages: ChatMessage[]
    appUrl: string | null
}

const SERVICE_IDENTIFIER = 'summit-42~org.kinotic.summit42.api.services.AppBuilderService'

/** The Summit 42 app builder the signed-in workspace chats with. */
export class AppBuilderService {

    private readonly serviceProxy: IServiceProxy

    constructor(kinotic: IKinotic) {
        this.serviceProxy = kinotic.serviceProxy(SERVICE_IDENTIFIER)
    }

    /** Sends a message; the events of the builder's turn follow, and the stream completes when the turn is over. */
    public chat(message: string): Observable<BuildEvent> {
        return this.serviceProxy.invokeStream('chat', [message])
    }

    /** The conversation so far, to render on load. */
    public findConversation(): Promise<Conversation> {
        return this.serviceProxy.invoke('findConversation')
    }

    /** Starts the conversation over. */
    public resetConversation(): Promise<void> {
        return this.serviceProxy.invoke('resetConversation')
    }
}

export const APP_BUILDER = new AppBuilderService(Kinotic)
