import { apiUrl } from '@/api/server'

/**
 * The Kinotic management gateway's browser auth routes. Every call sends credentials so the session cookie the
 * gateway sets on a successful login or sign-up is stored, and later calls and the STOMP handshake present it.
 */

/** A sign-in method an organization's login offers, as GET /api/auth/org/login/:orgId/providers lists it. */
export interface LoginProvider {
    id: string
    name: string | null
    provider: string | null
}

const WORKSPACE_KEY = 'summit42.organizationId'
const SIGNUP_NAME_KEY = 'summit42.signupDisplayName'

function readStored(key: string): string | null {
    try {
        return localStorage.getItem(key)
    } catch {
        return null
    }
}

function writeStored(key: string, value: string | null): void {
    try {
        if (value === null) {
            localStorage.removeItem(key)
        } else {
            localStorage.setItem(key, value)
        }
    } catch {
        // storage blocked (private mode): the value is only a convenience
    }
}

/** The id of the organization (workspace) this browser last signed in to, or null. */
export function rememberedWorkspace(): string | null {
    return readStored(WORKSPACE_KEY)
}

export function rememberWorkspace(organizationId: string | null): void {
    writeStored(WORKSPACE_KEY, organizationId)
}

/** The display name given at email sign-up, kept to prefill the workspace name once the email is verified. */
export function rememberedSignupName(): string | null {
    return readStored(SIGNUP_NAME_KEY)
}

export function rememberSignupName(displayName: string | null): void {
    writeStored(SIGNUP_NAME_KEY, displayName)
}

/** The gateway's {"error": "..."} message from a failed response, or the fallback. */
async function readError(res: Response, fallback: string): Promise<string> {
    let ret = fallback
    try {
        const body = await res.json()
        if (body && typeof body.error === 'string' && body.error) {
            ret = body.error
        }
    } catch {
        // not JSON
    }
    return ret
}

async function postJson(path: string, body: unknown, fallbackError: string): Promise<Response> {
    const res = await fetch(apiUrl(path), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body),
    })
    if (!res.ok) {
        throw new Error(await readError(res, fallbackError))
    }
    return res
}

/**
 * Whether the session cookie holds a login for this page: true for 204, false for 401. Rejects when the
 * gateway cannot be reached or answers anything else.
 */
export async function hasSession(): Promise<boolean> {
    const res = await fetch(apiUrl('/api/auth/me'), { credentials: 'include' })
    if (res.status !== 204 && res.status !== 401 && !res.ok) {
        throw new Error(`The session check answered ${res.status}`)
    }
    return res.ok
}

/** Ends this page's login. */
export async function logout(): Promise<void> {
    const res = await fetch(apiUrl('/api/auth/logout'), { method: 'POST', credentials: 'include' })
    if (!res.ok) {
        throw new Error('Sign-out failed. Please try again.')
    }
}

/** Signs a member of the organization in with email and password, establishing the session. */
export async function loginWithPassword(organizationId: string, email: string, password: string): Promise<void> {
    await postJson(`/api/auth/org/login/${encodeURIComponent(organizationId)}`, { email, password }, 'Invalid credentials')
}

/** The provider keys (e.g. "github") of the platform's social registrations, offered when no workspace is known. */
export async function fetchSocialProviders(): Promise<string[]> {
    let ret: string[] = []
    const res = await fetch(apiUrl('/api/auth/org/login/providers'))
    if (res.ok) {
        const data = await res.json()
        if (Array.isArray(data)) {
            ret = data.filter((p): p is string => typeof p === 'string' && p.length > 0)
        }
    }
    return ret
}

/** The sign-in methods the organization's login offers, or null when no organization has that id. */
export async function fetchWorkspaceProviders(organizationId: string): Promise<LoginProvider[] | null> {
    let ret: LoginProvider[] | null = []
    const res = await fetch(apiUrl(`/api/auth/org/login/${encodeURIComponent(organizationId)}/providers`))
    if (res.status === 404) {
        ret = null
    } else if (res.ok) {
        const data = await res.json()
        if (Array.isArray(data)) {
            ret = data.filter((p): p is LoginProvider => p && typeof p.id === 'string')
        }
    }
    return ret
}

/**
 * The organizations a social sign-in found the identity a member of, offered for a short while after the gateway
 * redirected back with error=multiple_organizations.
 */
export async function fetchWorkspaceChoices(): Promise<string[]> {
    let ret: string[] = []
    const res = await fetch(apiUrl('/api/auth/org/login/choices'), { credentials: 'include' })
    if (res.ok) {
        const data = await res.json()
        if (Array.isArray(data)) {
            ret = data.filter((id): id is string => typeof id === 'string' && id.length > 0)
        }
    }
    return ret
}

// the gateway sends the browser back to the referer path of this UI once the sign-in completes
function withReferer(url: string, referer: string | null): string {
    return referer ? `${url}?referer=${encodeURIComponent(referer)}` : url
}

/**
 * Form action that starts a social sign-in naming no organization; it returns to this UI's origin, at the referer
 * path when one is given.
 */
export function socialLoginUrl(provider: string, referer: string | null): string {
    return withReferer(apiUrl('/api/auth/org/login/social/start/' + encodeURIComponent(provider)), referer)
}

/** Form action that starts a sign-in through one of the organization's configurations, returning as socialLoginUrl does. */
export function workspaceLoginUrl(organizationId: string, configurationId: string, referer: string | null): string {
    return withReferer(apiUrl(`/api/auth/org/login/${encodeURIComponent(organizationId)}/start/${encodeURIComponent(configurationId)}`),
                       referer)
}

/** Form action that starts a social sign-up; it returns to this UI's /register?token=... */
export function socialSignupUrl(provider: string): string {
    return apiUrl('/api/auth/org/signup/social/start/' + encodeURIComponent(provider))
}

/** Starts an email sign-up: the gateway emails a link to this UI's /signup/verify?token=... */
export async function signUp(email: string, displayName: string): Promise<void> {
    await postJson('/api/auth/org/signup', { email, displayName }, 'Sign-up failed')
}

/** Finishes an email sign-up, creating the workspace and the account's password, and establishes the session. */
export async function completeSignUp(token: string, orgName: string, password: string): Promise<void> {
    await postJson('/api/auth/org/signup/complete', { token, orgName, orgDescription: null, password }, 'Could not create your workspace')
}

/** Finishes a social sign-up, creating the workspace, and establishes the session. */
export async function completeSocialSignUp(token: string, orgName: string): Promise<void> {
    await postJson('/api/auth/org/signup/social/complete', { token, orgName, orgDescription: null }, 'Could not create your workspace')
}

/** The name a provider key is shown by on its button. */
export function providerDisplayName(provider: string | null): string {
    let ret: string
    switch (provider) {
        case 'azure-ad': ret = 'Microsoft'; break
        case 'github': ret = 'GitHub'; break
        case 'google': ret = 'Google'; break
        case null: ret = 'your provider'; break
        default: ret = provider.split('-').map(s => s.charAt(0).toUpperCase() + s.slice(1)).join(' ')
    }
    return ret
}

/** The message for an ?error= code the gateway's redirects put on /login. */
export function authErrorMessage(code: string): string {
    let ret: string
    switch (code) {
        case 'no_account': ret = 'No account is linked to that sign-in. Create one, or sign in with your workspace id and password.'; break
        case 'multiple_organizations': ret = 'Your account belongs to several workspaces, but the choice expired. Please sign in again.'; break
        case 'account_exists': ret = 'An account already exists for that email or identity. Sign in instead.'; break
        case 'account_disabled': ret = 'This account cannot sign in to the workspace.'; break
        case 'email_not_verified': ret = 'Your provider has not verified your email address.'; break
        case 'state_mismatch': ret = 'The sign-in expired. Please try again.'; break
        case 'access_denied': ret = 'The sign-in was declined. Please try again.'; break
        case 'signup_failed': ret = 'Sign-up failed. Please try again.'; break
        case 'session_ended': ret = 'Your session ended. Please sign in again.'; break
        case 'unreachable': ret = 'Summit 42 could not reach its server. Please try again shortly.'; break
        case 'exchange_failed':
        case 'lookup_failed':
        case 'invalid_callback':
        case 'invalid_token': ret = 'Sign-in failed. Please try again.'; break
        default: ret = `Sign-in failed (${code}).`
    }
    return ret
}
