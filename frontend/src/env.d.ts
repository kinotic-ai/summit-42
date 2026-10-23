/// <reference types="vite/client" />

interface ImportMetaEnv {
    /** Host of the Kinotic management gateway; defaults to localhost. */
    readonly VITE_KINOTIC_HOST?: string
    /** Port of the Kinotic management gateway; defaults to 58503. */
    readonly VITE_KINOTIC_PORT?: string
    /** "true" to reach the gateway over TLS; defaults to false. */
    readonly VITE_KINOTIC_USE_SSL?: string
}

interface ImportMeta {
    readonly env: ImportMetaEnv
}
