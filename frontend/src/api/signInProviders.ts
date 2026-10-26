import { Kinotic, Pageable } from '@kinotic-ai/core'
import {
  ClientRegistrationType,
  OidcConfigurationType,
  type OwnedOidcConfiguration,
  type PasswordConfiguration,
} from '@kinotic-ai/management-api'

/**
 * One sign-in provider of a login of the organization's: its own login when the provider's applicationId is null,
 * otherwise one of its apps'. An identity provider is an OIDC configuration; an email and a password is the login's
 * password configuration, which a login has at most one of.
 */
export type SignInProvider =
    | { kind: 'oidc'; configuration: OwnedOidcConfiguration }
    | { kind: 'password'; configuration: PasswordConfiguration }

/** The name an email and a password is listed by among a login's providers. */
export const PASSWORD_PROVIDER_NAME = 'Email and password'

// an organization holds few providers, so each kind is read in one page and filtered to the login here
const MAX_PROVIDERS = 1000

/** The providers of the organization's own login, or of the app's login when applicationId is given, passwords first. */
export async function findSignInProviders(applicationId: string | null): Promise<SignInProvider[]> {
    const [oidc, passwords] = await Promise.all([
        Kinotic.ownedOidcConfigurations.findAll(Pageable.create(0, MAX_PROVIDERS)),
        Kinotic.ownedPasswordConfigurations.findAll(Pageable.create(0, MAX_PROVIDERS)),
    ])
    const ret: SignInProvider[] = []
    for (const configuration of passwords.content ?? []) {
        if ((configuration.applicationId ?? null) === applicationId && !configuration.tenantId) {
            ret.push({ kind: 'password', configuration })
        }
    }
    for (const configuration of oidc.content ?? []) {
        const ofLogin = applicationId === null
            ? configuration.type === OidcConfigurationType.ORGANIZATION
            : configuration.type === OidcConfigurationType.APPLICATION && configuration.applicationId === applicationId
        if (ofLogin) {
            ret.push({ kind: 'oidc', configuration })
        }
    }
    return ret
}

/** Saves a provider as it is, keeping the client secret held for an identity provider. */
export async function saveSignInProvider(provider: SignInProvider): Promise<void> {
    if (provider.kind === 'password') {
        await Kinotic.ownedPasswordConfigurations.save(provider.configuration)
    } else {
        await Kinotic.ownedOidcConfigurations.save(provider.configuration, null)
    }
}

/** Deletes a provider, with the client secret held for an identity provider. */
export async function deleteSignInProvider(provider: SignInProvider): Promise<void> {
    if (provider.kind === 'password') {
        await Kinotic.ownedPasswordConfigurations.deleteById(provider.configuration.id!)
    } else {
        await Kinotic.ownedOidcConfigurations.deleteById(provider.configuration.id!)
    }
}

/** Whether an identity provider signs in through Kinotic's registration, which needs no credentials of the organization's. */
export function isManaged(configuration: OwnedOidcConfiguration): boolean {
    return configuration.clientRegistrationType === ClientRegistrationType.MANAGED
}

/** Whether a provider of an app's login signs new people up, which every provider of the app's login says alike. */
export function signsUp(provider: SignInProvider): boolean {
    return provider.configuration.createUserOnSignIn
}
