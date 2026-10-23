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

/** The provider key of GitHub, which every organization signs up with. */
export const GITHUB_PROVIDER = 'github'

// the organization this browser last signed in to, so a returning member only confirms it
const LAST_ORGANIZATION_KEY = 'summit42.lastOrganization'

/** The id of the organization this browser last signed in to, or null. */
export function rememberedOrganization(): string | null {
    let ret: string | null = null
    try {
        ret = localStorage.getItem(LAST_ORGANIZATION_KEY)
    } catch {
        // storage blocked
    }
    return ret
}

/** Records the organization this browser signed in to. */
export function rememberOrganization(organizationId: string): void {
    try {
        localStorage.setItem(LAST_ORGANIZATION_KEY, organizationId)
    } catch {
        // storage blocked
    }
}

/** Forgets the organization this browser signed in to, so sign-in starts from the choice of organization. */
export function forgetOrganization(): void {
    try {
        localStorage.removeItem(LAST_ORGANIZATION_KEY)
    } catch {
        // storage blocked
    }
}

/**
 * The organization id named by what a person typed: the id itself, or the organization's name, whose slug is its
 * id. Empty when nothing was typed.
 */
export function toOrganizationId(typed: string): string {
    return typed.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').trim().toLowerCase()
        .replace(/[^a-z0-9_]+/g, '-').replace(/^-+|-+$/g, '')
}

/** The name a provider key (e.g. azure-ad) is shown by. */
export function providerDisplayName(provider: string | null): string {
    let ret: string
    switch (provider) {
        case 'azure-ad': ret = 'Microsoft'; break
        case 'github': ret = 'GitHub'; break
        case 'google': ret = 'Google'; break
        case 'okta': ret = 'Okta'; break
        case null: ret = 'your provider'; break
        default: ret = provider.charAt(0).toUpperCase() + provider.slice(1)
    }
    return ret
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

/** The provider keys (e.g. "github") of the platform's social registrations, offered when no organization is known. */
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
export async function fetchOrganizationProviders(organizationId: string): Promise<LoginProvider[] | null> {
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
export async function fetchOrganizationChoices(): Promise<string[]> {
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

/** The referer query value a sign-in returns to: only a path within this UI, null for anything else. */
export function refererOf(value: unknown): string | null {
    return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
        ? value
        : null
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
export function organizationLoginUrl(organizationId: string, configurationId: string, referer: string | null): string {
    return withReferer(apiUrl(`/api/auth/org/login/${encodeURIComponent(organizationId)}/start/${encodeURIComponent(configurationId)}`),
                       referer)
}

/** Signs the organization's member in with their email and password, establishing the session. */
export async function loginWithPassword(organizationId: string, email: string, password: string): Promise<void> {
    await postJson(`/api/auth/org/login/${encodeURIComponent(organizationId)}`, { email, password }, 'Invalid credentials')
}

/** Form action that starts a social sign-up; it returns to this UI's /register?token=... */
export function socialSignupUrl(provider: string): string {
    return apiUrl('/api/auth/org/signup/social/start/' + encodeURIComponent(provider))
}

/** Finishes a social sign-up, creating the organization, and establishes the session. */
export async function completeSocialSignUp(token: string, orgName: string): Promise<void> {
    await postJson('/api/auth/org/signup/social/complete', { token, orgName, orgDescription: null }, 'Could not create your organization')
}

/** An invitation as GET /api/auth/invite/details describes it to the invitee. */
export interface InviteDetails {
    email: string
    displayName: string | null
    organizationName: string | null
    /** Set for an invitation into one of the organization's apps, null for one into the organization. */
    applicationId: string | null
    invitedByName: string | null
    expiresAt: string
    /** Whether the invited email already has an account someone signs in as. */
    organizationUserExists: boolean
    /** Whether that account has a password, which accepting with a password must then match. */
    organizationUserHasPassword: boolean
    /** The sign-in methods the invitation can be accepted through. */
    providers: LoginProvider[]
}

/** What accepting an invitation into an app returns: the app's own sign-in page, or null while it has none. */
export interface AppInviteAccepted {
    applicationId: string
    primaryUiUrl: string | null
}

/** The invitation the emailed link's token names. Rejects with the gateway's message for an expired or used one. */
export async function fetchInvite(token: string): Promise<InviteDetails> {
    const res = await fetch(apiUrl('/api/auth/invite/details?token=' + encodeURIComponent(token)))
    if (!res.ok) {
        throw new Error(await readError(res, 'This invitation could not be loaded.'))
    }
    return await res.json()
}

/**
 * Accepts an invitation with a password: a new one for a new account, or the existing account's. Accepting one into
 * the organization establishes the session and resolves null; accepting one into an app resolves where its users sign in.
 */
export async function acceptInvite(token: string, password: string, displayName: string | null): Promise<AppInviteAccepted | null> {
    const res = await postJson('/api/auth/invite/accept', { token, password, displayName }, 'Could not accept the invitation')
    let ret: AppInviteAccepted | null = null
    if (res.status !== 204) {
        const body = await res.json()
        ret = { applicationId: body.applicationId, primaryUiUrl: body.primaryUiUrl ?? null }
    }
    return ret
}

/** Form action that accepts an organization invitation through a provider; it returns to this UI's / once accepted. */
export function inviteProviderUrl(configurationId: string, token: string): string {
    return apiUrl(`/api/auth/invite/oidc/start/${encodeURIComponent(configurationId)}?token=${encodeURIComponent(token)}`)
}

/** The message for an ?error= code the gateway's redirects put on /login or /invite/accept. */
export function authErrorMessage(code: string): string {
    let ret: string
    switch (code) {
        case 'no_user': ret = 'No account signs in with that identity. Create one first.'; break
        case 'multiple_organizations': ret = 'Your account belongs to several organizations, but the choice expired. Please sign in again.'; break
        case 'user_exists': ret = 'An account already exists for that email or identity. Sign in instead.'; break
        case 'user_pending': ret = 'You were invited to an organization. Accept the invitation emailed to you first.'; break
        case 'user_disabled': ret = 'This account cannot sign in to the organization.'; break
        case 'email_mismatch': ret = 'That account uses a different email from the one invited. Use an account with the invited address.'; break
        case 'invite_invalid': ret = 'This invitation is no longer valid. Ask whoever invited you for a new one.'; break
        case 'config_not_found': ret = 'That sign-in method is not offered for this invitation.'; break
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
