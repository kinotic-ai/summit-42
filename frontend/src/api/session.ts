import { reactive } from 'vue'
import { Kinotic, type IParticipant } from '@kinotic-ai/core'
import { hasSession, logout, rememberWorkspace } from '@/api/auth'
import { KINOTIC_SERVER } from '@/api/server'

/** The signed-in state every page reads. */
export const SESSION = reactive({
    /** True while the STOMP connection, authenticated by the session cookie, is open. */
    connected: false,
    /** The organization (workspace) the session is signed in to, once connected. */
    organizationId: null as string | null,
})

// run when an open connection ends and will not be retried
let sessionEndedListener: (() => void) | null = null

// the server sends every participant with its scope discriminator; an organization participant carries its id
function organizationIdOf(participant: IParticipant | undefined): string | null {
    const wire = participant as unknown as { type?: string; organizationId?: string } | undefined
    return wire?.type === 'organization' && typeof wire.organizationId === 'string' ? wire.organizationId : null
}

// the gateway answered the session check, so a connection that keeps failing is reported rather than retried forever
const MAX_CONNECTION_ATTEMPTS = 5

let inFlight: Promise<unknown> = Promise.resolve()

// connect and disconnect run one at a time, so only one connection is ever open
function serialize<T>(operation: () => Promise<T>): Promise<T> {
    const result = inFlight.then(operation)
    inFlight = result.catch(() => {})
    return result
}

/**
 * Opens the realtime connection with the session a REST login established, and learns the workspace from the
 * participant the gateway issues. Resolves false when the browser has no session; rejects when the gateway cannot
 * be reached.
 */
export function connect(): Promise<boolean> {
    return serialize(async () => {
        let ret = false
        if (SESSION.connected) {
            ret = true
        } else if (await hasSession()) {
            const connectedInfo = await Kinotic.connect({ server: KINOTIC_SERVER, maxConnectionAttempts: MAX_CONNECTION_ATTEMPTS })
            SESSION.organizationId = organizationIdOf(connectedInfo.participant)
            if (SESSION.organizationId) {
                rememberWorkspace(SESSION.organizationId)
            }
            SESSION.connected = true
            ret = true
        }
        return ret
    })
}

/** Ends the session and closes the realtime connection. */
export function signOut(): Promise<void> {
    return serialize(async () => {
        // cleared before the disconnect so the connection handler does not read it as a lost session
        SESSION.connected = false
        SESSION.organizationId = null
        try {
            await logout()
        } finally {
            await Kinotic.disconnect().catch(() => {})
        }
    })
}

/** Registers the callback run when the connection is lost for good, as when the session expires. */
export function onSessionEnded(listener: () => void): void {
    sessionEndedListener = listener
}

Kinotic.eventBus.connectionEnded.subscribe(() => {
    // a connection the client is retrying on its own keeps the session; signOut clears connected first
    if (SESSION.connected && !Kinotic.eventBus.isConnectionActive()) {
        SESSION.connected = false
        sessionEndedListener?.()
    }
})
