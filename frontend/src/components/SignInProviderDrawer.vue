<template>
  <SideDrawer :title="title" :subtitle="subtitle" @close="emit('close')">
    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <!-- a provider with the organization's own credentials works once its callback URL is registered at the provider -->
    <div v-if="added">
      <p class="muted note">
        Add this callback URL to the app registration at {{ providerDisplayName(added.provider) }} as a redirect URI.
        Sign-ins through it fail until it is registered there.
      </p>
      <CopyableUrl :url="callbackUrl" />
    </div>

    <form v-else id="provider-form" @submit.prevent="submit">
      <div class="field">
        <label for="provider-type">Provider</label>
        <select v-if="!editing" id="provider-type" v-model="choice" class="input">
          <option v-for="offered in choices" :key="offered" :value="offered">{{ choiceName(offered) }}</option>
        </select>
        <input v-else id="provider-type" class="input" :value="choiceName(choice)" readonly />
      </div>

      <template v-if="choice !== PASSWORD">
        <div class="field">
          <label for="provider-name">Button name</label>
          <input id="provider-name" v-model="name" class="input" autocomplete="off" :placeholder="namePlaceholder" required />
          <span class="hint">The sign-in page shows "Continue with" this name.</span>
        </div>

        <div v-if="managedOffered" class="field">
          <span class="label">Credentials</span>
          <div class="choices">
            <label v-for="option in REGISTRATION_OPTIONS" :key="option.value" :class="['choice', { 'choice--chosen': registration === option.value }]">
              <input v-model="registration" type="radio" name="registration" :value="option.value" />
              <span><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
            </label>
          </div>
        </div>

        <template v-if="!managed">
          <div v-if="offered?.providerAccountLabel" class="field">
            <label for="provider-account">{{ offered.providerAccountLabel }}</label>
            <input id="provider-account"
                   v-model="providerAccount"
                   class="input mono"
                   autocomplete="off"
                   :placeholder="ACCOUNT_PLACEHOLDERS[provider]"
                   required />
            <span v-if="accountInvalid" class="hint hint--error">Enter it in the form {{ ACCOUNT_PLACEHOLDERS[provider] }}.</span>
          </div>

          <div class="field">
            <label for="provider-client-id">Client ID</label>
            <input id="provider-client-id" v-model="clientId" class="input mono" autocomplete="off" required />
          </div>

          <div class="field">
            <label for="provider-secret">Client secret</label>
            <input id="provider-secret"
                   v-model="clientSecret"
                   class="input"
                   type="password"
                   autocomplete="new-password"
                   :placeholder="secretPlaceholder"
                   :disabled="choosingSecret && publicClient" />
            <label v-if="choosingSecret" class="check">
              <input v-model="publicClient" type="checkbox" /> Public client, with no secret
            </label>
            <span v-else-if="editingOidc?.confidentialClient" class="hint secret">
              Leave it empty to keep the secret held for this provider.
              <button type="button" class="link-button" :disabled="saving" @click="removeSecret">Remove the secret</button>
            </span>
          </div>

          <div class="field">
            <label for="provider-audience">Audience <span class="muted">(optional)</span></label>
            <input id="provider-audience" v-model="audience" class="input mono" autocomplete="off" />
            <span class="hint">The aud claim the provider's tokens carry, when it is not the client ID.</span>
          </div>
        </template>
      </template>

      <label class="check">
        <input v-model="enabled" type="checkbox" /> Offer it on the sign-in page
      </label>

      <p v-if="applicationId !== null" class="hint">
        {{ openSignUp
          ? 'Anyone can sign up through it, since anyone can sign up to this app.'
          : 'Only the people you invite sign in through it, since this app is invite only.' }}
      </p>

      <div v-if="editingOidc && !managed" class="field callback">
        <label>Callback URL</label>
        <CopyableUrl :url="callbackUrl" />
        <span class="hint">The redirect URI registered at the provider.</span>
      </div>
    </form>

    <template #footer>
      <button v-if="added" type="button" class="btn" @click="emit('close')">Done</button>
      <template v-else>
        <button type="button" class="btn btn--secondary" :disabled="saving" @click="emit('close')">Cancel</button>
        <button type="submit" form="provider-form" class="btn" :disabled="saving || !complete">
          <span v-if="saving" class="spinner" />
          {{ editing ? 'Save' : 'Add provider' }}
        </button>
      </template>
    </template>
  </SideDrawer>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Kinotic } from '@kinotic-ai/core'
import {
  ClientRegistrationType,
  findOrganizationOidcProvider,
  isProviderAccountValid,
  OidcConfigurationType,
  OidcProviderType,
  type OwnedOidcConfiguration,
  type PasswordConfiguration,
} from '@kinotic-ai/management-api'
import CopyableUrl from '@/components/CopyableUrl.vue'
import SideDrawer from '@/components/SideDrawer.vue'
import { providerDisplayName } from '@/api/auth'
import { errorText } from '@/api/format'
import { isManaged, PASSWORD_PROVIDER_NAME, type SignInProvider } from '@/api/signInProviders'

/**
 * Adds or edits a sign-in provider of the organization's own login, or of one of its apps' when applicationId is
 * given: an identity provider, or an email and a password while the login has none. An app's provider may sign in
 * through Kinotic's registration at Google or Microsoft, which needs no credentials. The secret held for a provider
 * is kept unless a new one is entered, and removed only through "Remove the secret".
 */
const props = defineProps<{
  /** The app whose login the provider belongs to, or null for the organization's own. */
  applicationId: string | null
  /** The provider to edit, or null to add one. */
  provider: SignInProvider | null
  /** Whether the login already has an email and a password, which it has at most one of. */
  passwordOffered: boolean
  /** Whether anyone may sign up to the app, which a provider of the app's login follows. */
  openSignUp: boolean
}>()

const emit = defineEmits<{
  /** Cancelled, or done after a save. */
  close: []
  /** A provider was added or changed. */
  saved: []
}>()

const PASSWORD = 'password' as const
/** What the provider list offers: an identity provider kind, or an email and a password. */
type ProviderChoice = OidcProviderType | typeof PASSWORD

const ACCOUNT_PLACEHOLDERS: Partial<Record<OidcProviderType, string>> = {
  [OidcProviderType.AZURE_AD]: '00000000-0000-0000-0000-000000000000',
  [OidcProviderType.OKTA]: 'acme.okta.com',
}

const REGISTRATION_OPTIONS = [
  { value: ClientRegistrationType.MANAGED, label: 'Managed by Kinotic', description: 'Works with no setup. The consent screen shows Kinotic\'s name.' },
  { value: ClientRegistrationType.OWN, label: 'Your own credentials', description: 'Your client ID and secret, so the consent screen shows your app\'s name.' },
]

// the kinds an organization's members sign in with, and those an app's users do, the likeliest first
const ORGANIZATION_KINDS = [OidcProviderType.OKTA, OidcProviderType.AZURE_AD, OidcProviderType.GOOGLE]
const APPLICATION_KINDS = [OidcProviderType.GOOGLE, OidcProviderType.AZURE_AD, OidcProviderType.OKTA]

const editing = props.provider
const editingOidc = editing?.type === 'OIDC' ? editing.configuration : null
const editingPassword = editing?.type === 'PASSWORD' ? editing.configuration : null
const kinds = props.applicationId === null ? ORGANIZATION_KINDS : APPLICATION_KINDS

const choice = ref<ProviderChoice>(editingPassword ? PASSWORD : editingOidc?.provider ?? kinds[0]!)
const registration = ref<ClientRegistrationType>(editingOidc?.clientRegistrationType ?? (editingOidc ? ClientRegistrationType.OWN : ClientRegistrationType.MANAGED))
const name = ref(editingOidc?.name ?? '')
const providerAccount = ref(editingOidc?.providerAccount ?? '')
const clientId = ref(editingOidc?.clientId ?? '')
const clientSecret = ref('')
const publicClient = ref(false)
const audience = ref(editingOidc?.audience ?? '')
const enabled = ref(editing?.configuration.enabled ?? true)
// the kinds an app's provider may sign in through Kinotic's registration for
const managedProviders = ref<OidcProviderType[]>([])
const saving = ref(false)
const error = ref<string | null>(null)
const added = ref<OwnedOidcConfiguration | null>(null)
const callbackUrl = ref<string | null>(null)

const choices = computed<ProviderChoice[]>(() => [...kinds, ...(props.passwordOffered ? [] : [PASSWORD])])
const provider = computed<OidcProviderType>(() => choice.value === PASSWORD ? kinds[0]! : choice.value)
const offered = computed(() => findOrganizationOidcProvider(provider.value))
// only an app's login signs in through Kinotic's registration; a provider using it keeps the choice while the list loads
const managedOffered = computed(() => props.applicationId !== null && choice.value !== PASSWORD
    && (managedProviders.value.includes(provider.value) || (editingOidc !== null && isManaged(editingOidc))))
const managed = computed(() => managedOffered.value && registration.value === ClientRegistrationType.MANAGED)
// a client of its own gets a secret, or is declared public, when it is added and when a provider that holds none
// stops using Kinotic's registration
const choosingSecret = computed(() => !managed.value && (editingOidc === null || (isManaged(editingOidc) && !editingOidc.confidentialClient)))
const accountValue = computed(() => offered.value?.providerAccountLabel ? providerAccount.value.trim() : null)
const accountInvalid = computed(() => !!accountValue.value && !isProviderAccountValid(provider.value, accountValue.value))
const complete = computed(() => choice.value === PASSWORD || (name.value.trim() !== '' && (managed.value
    || (clientId.value.trim() !== '' && isProviderAccountValid(provider.value, accountValue.value)
        && (!choosingSecret.value || publicClient.value || clientSecret.value !== '')))))

const title = computed(() => added.value ? 'Provider added' : editing ? 'Edit provider' : 'Add provider')
const subtitle = computed(() => {
  let ret: string
  if (added.value) {
    ret = `${added.value.name} is saved.`
  } else if (props.applicationId === null) {
    ret = 'A way your organization\'s members sign in to Summit 42.'
  } else {
    ret = 'A way this app\'s users sign in to it.'
  }
  return ret
})
const namePlaceholder = computed(() => provider.value === OidcProviderType.OKTA ? 'Acme Okta' : providerDisplayName(provider.value))
const secretPlaceholder = computed(() => {
  let ret: string
  if (choosingSecret.value) {
    ret = publicClient.value ? 'No secret' : 'The secret the provider issued'
  } else if (editingOidc?.confidentialClient) {
    ret = 'A secret is held'
  } else {
    ret = 'None held; enter one to send it'
  }
  return ret
})

onMounted(() => {
  if (editingOidc?.id) {
    void loadCallbackUrl(editingOidc.id)
  }
  if (props.applicationId !== null) {
    void loadManagedProviders()
  }
})

// Microsoft's provider key is azure-ad, whose product is Microsoft Entra ID
function choiceName(chosen: ProviderChoice): string {
  let ret: string
  if (chosen === PASSWORD) {
    ret = PASSWORD_PROVIDER_NAME
  } else if (chosen === OidcProviderType.AZURE_AD) {
    ret = 'Microsoft Entra ID'
  } else {
    ret = providerDisplayName(chosen)
  }
  return ret
}

async function loadManagedProviders() {
  try {
    managedProviders.value = await Kinotic.ownedOidcConfigurations.findManagedProviders()
  } catch (err) {
    error.value = errorText(err, 'Could not load the providers Kinotic manages.')
  }
}

async function loadCallbackUrl(id: string) {
  try {
    callbackUrl.value = await Kinotic.ownedOidcConfigurations.findCallbackUrl(id)
  } catch (err) {
    error.value = errorText(err, 'Could not load the callback URL.')
  }
}

function oidcToSave(): OwnedOidcConfiguration {
  const fields = {
    name: name.value.trim(),
    // the organization's own credentials are kept while Kinotic's registration is used, for a switch back
    providerAccount: accountValue.value || null,
    clientId: clientId.value.trim() || null,
    audience: audience.value.trim() || null,
    enabled: enabled.value,
    createUserOnSignIn: props.applicationId !== null && props.openSignUp,
    ...(managedOffered.value ? { clientRegistrationType: registration.value } : {}),
  }
  return editingOidc
    ? { ...editingOidc, ...fields }
    : {
        ...fields,
        id: null,
        type: props.applicationId === null ? OidcConfigurationType.ORGANIZATION : OidcConfigurationType.APPLICATION,
        organizationId: null,
        applicationId: props.applicationId,
        tenantId: null,
        provider: provider.value,
        authority: null,
        confidentialClient: false,
        created: null,
        updated: null,
      }
}

function passwordToSave(): PasswordConfiguration {
  const fields = { enabled: enabled.value, createUserOnSignIn: props.applicationId !== null && props.openSignUp }
  return editingPassword
    ? { ...editingPassword, ...fields }
    : { ...fields, id: null, organizationId: null, applicationId: props.applicationId, tenantId: null, created: null, updated: null }
}

async function submit() {
  if (!complete.value || saving.value) return
  saving.value = true
  error.value = null
  try {
    if (choice.value === PASSWORD) {
      await Kinotic.ownedPasswordConfigurations.save(passwordToSave())
      emit('saved')
      emit('close')
    } else {
      const secret = managed.value || (choosingSecret.value && publicClient.value) ? null : clientSecret.value || null
      const saved = await Kinotic.ownedOidcConfigurations.save(oidcToSave(), secret)
      emit('saved')
      // Kinotic's registration needs nothing registered at the provider, so there is no callback URL to show
      if (editing || managed.value) {
        emit('close')
      } else {
        added.value = saved
        await loadCallbackUrl(saved.id!)
      }
    }
  } catch (err) {
    error.value = errorText(err, 'Could not save the provider.')
  } finally {
    saving.value = false
  }
}

async function removeSecret() {
  if (!editingOidc?.id) return
  if (!window.confirm(`Remove the client secret held for ${editingOidc.name}? Sign-ins through it then send no secret, which the provider refuses unless its app registration is a public client.`)) return
  saving.value = true
  error.value = null
  try {
    await Kinotic.ownedOidcConfigurations.deleteClientSecret(editingOidc.id)
    emit('saved')
    emit('close')
  } catch (err) {
    error.value = errorText(err, 'Could not remove the client secret.')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.note {
  margin: 0 0 1rem;
  font-size: 0.9rem;
}

.label {
  display: block;
  margin-bottom: 0.4rem;
  font-size: 0.875rem;
  font-weight: 500;
}

.check {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0.15rem 0 1rem;
  font-size: 0.9rem;
  color: var(--text-2);
  cursor: pointer;
}

.field .check {
  margin: 0.25rem 0 0;
  font-weight: 400;
}

.check input {
  accent-color: var(--accent);
}

.secret {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.5rem;
}

.hint--error {
  color: var(--danger-text);
}

.callback {
  margin-top: 0.5rem;
}

select.input {
  appearance: auto;
}
</style>
