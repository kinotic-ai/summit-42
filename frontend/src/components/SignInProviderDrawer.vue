<template>
  <SideDrawer :title="title" :subtitle="subtitle" @close="emit('close')">
    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <!-- a new provider works once its callback URL is registered at the provider, so the drawer shows it after the save -->
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
        <select v-if="!configuration" id="provider-type" v-model="provider" class="input">
          <option v-for="offered in ORGANIZATION_OIDC_PROVIDERS" :key="offered.provider" :value="offered.provider">
            {{ providerName(offered.provider) }}
          </option>
        </select>
        <input v-else id="provider-type" class="input" :value="providerName(provider)" readonly />
      </div>

      <div class="field">
        <label for="provider-name">Button name</label>
        <input id="provider-name" v-model="name" class="input" autocomplete="off" placeholder="Acme Okta" required />
        <span class="hint">The sign-in page shows "Continue with" this name.</span>
      </div>

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
               :disabled="!configuration && publicClient" />
        <label v-if="!configuration" class="check">
          <input v-model="publicClient" type="checkbox" /> Public client, with no secret
        </label>
        <span v-else-if="configuration.confidentialClient" class="hint secret">
          Leave it empty to keep the secret held for this provider.
          <button type="button" class="link-button" :disabled="saving" @click="removeSecret">Remove the secret</button>
        </span>
      </div>

      <div class="field">
        <label for="provider-audience">Audience <span class="muted">(optional)</span></label>
        <input id="provider-audience" v-model="audience" class="input mono" autocomplete="off" />
        <span class="hint">The aud claim the provider's tokens carry, when it is not the client ID.</span>
      </div>

      <label class="check">
        <input v-model="enabled" type="checkbox" /> Offer it on the sign-in page
      </label>

      <div v-if="configuration" class="field callback">
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
          {{ configuration ? 'Save' : 'Add provider' }}
        </button>
      </template>
    </template>
  </SideDrawer>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Kinotic } from '@kinotic-ai/core'
import {
  findOrganizationOidcProvider,
  isProviderAccountValid,
  OidcConfigurationType,
  OidcProviderType,
  ORGANIZATION_OIDC_PROVIDERS,
  type OwnedOidcConfiguration,
} from '@kinotic-ai/management-api'
import CopyableUrl from '@/components/CopyableUrl.vue'
import SideDrawer from '@/components/SideDrawer.vue'
import { providerDisplayName } from '@/api/auth'
import { errorText } from '@/api/format'

/**
 * Adds or edits one of the identity providers the organization's members sign in with. The secret held for a
 * provider is kept unless a new one is entered, and removed only through "Remove the secret".
 */
const props = defineProps<{
  /** The provider to edit, or null to add one. */
  configuration: OwnedOidcConfiguration | null
}>()

const emit = defineEmits<{
  /** Cancelled, or done after a save. */
  close: []
  /** A provider was added or changed. */
  saved: []
}>()

const ACCOUNT_PLACEHOLDERS: Partial<Record<OidcProviderType, string>> = {
  [OidcProviderType.AZURE_AD]: '00000000-0000-0000-0000-000000000000',
  [OidcProviderType.OKTA]: 'acme.okta.com',
}

const editing = props.configuration
const provider = ref<OidcProviderType>(editing?.provider ?? OidcProviderType.OKTA)
const name = ref(editing?.name ?? '')
const providerAccount = ref(editing?.providerAccount ?? '')
const clientId = ref(editing?.clientId ?? '')
const clientSecret = ref('')
const publicClient = ref(false)
const audience = ref(editing?.audience ?? '')
const enabled = ref(editing?.enabled ?? true)
const saving = ref(false)
const error = ref<string | null>(null)
const added = ref<OwnedOidcConfiguration | null>(null)
const callbackUrl = ref<string | null>(null)

const offered = computed(() => findOrganizationOidcProvider(provider.value))
const accountValue = computed(() => offered.value?.providerAccountLabel ? providerAccount.value.trim() : null)
const accountInvalid = computed(() => !!accountValue.value && !isProviderAccountValid(provider.value, accountValue.value))
const complete = computed(() => name.value.trim() !== '' && clientId.value.trim() !== ''
    && isProviderAccountValid(provider.value, accountValue.value)
    && (editing !== null || publicClient.value || clientSecret.value !== ''))

const title = computed(() => added.value ? 'Provider added' : editing ? 'Edit provider' : 'Add provider')
const subtitle = computed(() => added.value
    ? `${added.value.name} is saved.`
    : 'An identity provider your organization\'s members sign in with.')
const secretPlaceholder = computed(() => {
  let ret: string
  if (!editing) {
    ret = publicClient.value ? 'No secret' : 'The secret the provider issued'
  } else if (editing.confidentialClient) {
    ret = 'A secret is held'
  } else {
    ret = 'None held; enter one to send it'
  }
  return ret
})

onMounted(() => {
  if (editing?.id) {
    void loadCallbackUrl(editing.id)
  }
})

// Microsoft's provider key is azure-ad, whose product is Microsoft Entra ID
function providerName(type: OidcProviderType): string {
  return type === OidcProviderType.AZURE_AD ? 'Microsoft Entra ID' : providerDisplayName(type)
}

async function loadCallbackUrl(id: string) {
  try {
    callbackUrl.value = await Kinotic.ownedOidcConfigurations.findCallbackUrl(id)
  } catch (err) {
    error.value = errorText(err, 'Could not load the callback URL.')
  }
}

function toSave(): OwnedOidcConfiguration {
  const fields = {
    name: name.value.trim(),
    providerAccount: accountValue.value,
    clientId: clientId.value.trim(),
    audience: audience.value.trim() || null,
    enabled: enabled.value,
  }
  return editing
    ? { ...editing, ...fields }
    : {
        ...fields,
        id: null,
        type: OidcConfigurationType.ORGANIZATION,
        organizationId: null,
        applicationId: null,
        tenantId: null,
        provider: provider.value,
        authority: null,
        confidentialClient: false,
        createUserOnSignIn: false,
        created: null,
        updated: null,
      }
}

async function submit() {
  if (!complete.value || saving.value) return
  saving.value = true
  error.value = null
  try {
    const secret = !editing && publicClient.value ? null : clientSecret.value || null
    const saved = await Kinotic.ownedOidcConfigurations.save(toSave(), secret)
    emit('saved')
    if (editing) {
      emit('close')
    } else {
      added.value = saved
      await loadCallbackUrl(saved.id!)
    }
  } catch (err) {
    error.value = errorText(err, 'Could not save the provider.')
  } finally {
    saving.value = false
  }
}

async function removeSecret() {
  if (!editing?.id) return
  if (!window.confirm(`Remove the client secret held for ${editing.name}? Sign-ins through it then send no secret, which the provider refuses unless its app registration is a public client.`)) return
  saving.value = true
  error.value = null
  try {
    await Kinotic.ownedOidcConfigurations.deleteClientSecret(editing.id)
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
