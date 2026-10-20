import type { ServerInfo } from '@kinotic-ai/core'

/**
 * The Kinotic management gateway this UI signs in to: REST calls and the STOMP connection both go to it.
 * Read from VITE_KINOTIC_HOST, VITE_KINOTIC_PORT and VITE_KINOTIC_USE_SSL at build time.
 */
export const KINOTIC_SERVER: ServerInfo = {
    host: import.meta.env.VITE_KINOTIC_HOST || 'localhost',
    port: import.meta.env.VITE_KINOTIC_PORT ? parseInt(import.meta.env.VITE_KINOTIC_PORT, 10) : 58503,
    useSSL: import.meta.env.VITE_KINOTIC_USE_SSL === 'true',
}

/** The absolute URL of a REST route on the gateway, e.g. apiUrl('/api/auth/me'). */
export function apiUrl(path: string): string {
    const { host, port, useSSL } = KINOTIC_SERVER
    return `${useSSL ? 'https' : 'http'}://${host}:${port}${path.startsWith('/') ? path : '/' + path}`
}
