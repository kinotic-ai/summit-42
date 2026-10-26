<template>
  <section class="panel panel--flush glass">
    <div class="panel__head">
      <div class="intro">
        <h2 class="panel__title">Sign-in providers</h2>
        <p class="muted panel__note">{{ intro }}</p>
      </div>
      <button type="button" class="btn btn--small" @click="openDrawer(null)"><AppIcon name="plus" :stroke-width="2.25" /> Add provider</button>
    </div>

    <div v-if="error" class="alert alert--error" role="alert">
      {{ error }}
      <button type="button" class="link-button" @click="load">Try again</button>
    </div>

    <!-- an app's choice between invitation-only and open sign-up, which every provider of its login follows -->
    <div v-if="applicationId !== null" class="access">
      <span class="access__label">Who can sign in</span>
      <div class="choices" role="radiogroup" aria-label="Who can sign in">
        <label v-for="option in ACCESS_OPTIONS" :key="String(option.value)" :class="['choice', { 'choice--chosen': chosen === option.value }]">
          <input v-model="chosen"
                 type="radio"
                 name="access"
                 :value="option.value"
                 :disabled="loading || busy !== null"
                 @change="setOpenSignUp(option.value)" />
          <span><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
        </label>
      </div>
    </div>

    <div class="table-wrap">
      <table v-if="rows.length > 0" class="table">
        <thead><tr><th>Name</th><th>Provider</th><th>Status</th><th /></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>
              <button type="button" class="link-button" @click="openDrawer(row.provider)">{{ row.name }}</button>
              <span v-if="row.detail" class="table__sub mono">{{ row.detail }}</span>
            </td>
            <td class="muted">{{ row.providerName }}</td>
            <td><span :class="['chip', row.enabled ? 'chip--live' : '']">{{ row.enabled ? 'Offered' : 'Off' }}</span></td>
            <td>
              <div class="table__actions">
                <button type="button" class="btn btn--secondary btn--small" :disabled="busy === row.id" @click="openDrawer(row.provider)">Edit</button>
                <button type="button" class="btn btn--secondary btn--small" :disabled="busy === row.id" @click="setEnabled(row.provider, !row.enabled)">
                  {{ row.enabled ? 'Turn off' : 'Turn on' }}
                </button>
                <!-- an app keeps its email and password, which carries who can sign in; turning it off is enough -->
                <button v-if="row.provider.type === 'OIDC' || applicationId === null"
                        type="button"
                        class="btn btn--danger btn--small"
                        :disabled="busy === row.id"
                        @click="remove(row.provider)">Delete</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="table__empty">{{ loading ? 'Loading…' : emptyText }}</p>
    </div>

    <SignInProviderDrawer v-if="drawerOpen"
                          :application-id="applicationId"
                          :provider="editing"
                          :password-offered="providers.some(provider => provider.type === 'PASSWORD')"
                          :open-sign-up="openSignUp"
                          @saved="load"
                          @close="drawerOpen = false" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SignInProviderDrawer from '@/components/SignInProviderDrawer.vue'
import { APP_BUILDER } from '@/api/appBuilder'
import { providerDisplayName } from '@/api/auth'
import { errorText } from '@/api/format'
import {
  deleteSignInProvider,
  findSignInProviders,
  isManaged,
  openSignUpOf,
  PASSWORD_PROVIDER_NAME,
  saveSignInProvider,
  type SignInProvider,
} from '@/api/signInProviders'

/**
 * The sign-in providers of the organization's own login, or of an app's when applicationId is given, with adding,
 * editing, turning on or off and deleting them. An app's panel also chooses who can sign in: only the people invited
 * on its Users page, or anyone who signs up, which the server sets on every provider of the app's login.
 */
const props = defineProps<{
  applicationId: string | null
}>()

/** One row of the table. */
interface ProviderRow {
  id: string
  provider: SignInProvider
  name: string
  providerName: string
  /** The account at the provider, or null. */
  detail: string | null
  enabled: boolean
}

const ACCESS_OPTIONS = [
  { value: false, label: 'People you invite', description: 'Only the people invited on the Users page can sign in.' },
  { value: true, label: 'Anyone who signs up', description: 'Anyone can create an account at the app, with an email and a password or any provider below.' },
]

const loading = ref(true)
const error = ref<string | null>(null)
const providers = ref<SignInProvider[]>([])
const busy = ref<string | null>(null)
const drawerOpen = ref(false)
const editing = ref<SignInProvider | null>(null)

const openSignUp = computed(() => openSignUpOf(providers.value))
// the radio's own state, so a choice the server refused snaps back to what the app has
const chosen = ref(false)
const rows = computed<ProviderRow[]>(() => providers.value.map(toRow))

const intro = computed(() => props.applicationId === null
    ? 'Members sign in with GitHub. Add your own identity provider, such as Okta, Microsoft Entra ID or Google Workspace, or an email and a password, and they can sign in with that too.'
    : 'How people sign in to this app: an email and a password, Google or Microsoft through Kinotic with no setup, or an identity provider with your own credentials.')
const emptyText = computed(() => props.applicationId === null
    ? 'No providers of your own yet.'
    : 'No way to sign in yet. Add one before inviting anyone.')

onMounted(load)

watch(openSignUp, open => { chosen.value = open }, { immediate: true })

function toRow(provider: SignInProvider): ProviderRow {
  let ret: ProviderRow
  if (provider.type === 'PASSWORD') {
    ret = { id: provider.configuration.id!, provider, name: PASSWORD_PROVIDER_NAME, providerName: 'Password', detail: null,
            enabled: provider.configuration.enabled }
  } else {
    const configuration = provider.configuration
    ret = { id: configuration.id!, provider, name: configuration.name, providerName: providerDisplayName(configuration.provider),
            detail: isManaged(configuration) ? 'Managed by Kinotic' : configuration.providerAccount, enabled: configuration.enabled }
  }
  return ret
}

async function load() {
  loading.value = true
  error.value = null
  try {
    providers.value = await findSignInProviders(props.applicationId)
  } catch (err) {
    error.value = errorText(err, 'Could not load the sign-in providers.')
  } finally {
    loading.value = false
  }
}

function openDrawer(provider: SignInProvider | null) {
  editing.value = provider
  drawerOpen.value = true
}

async function act(id: string, action: () => Promise<unknown>, fallback: string) {
  busy.value = id
  error.value = null
  try {
    await action()
    await load()
  } catch (err) {
    error.value = errorText(err, fallback)
    chosen.value = openSignUp.value
  } finally {
    busy.value = null
  }
}

// the server keeps every provider of the app's login saying the same; the drawer gives a provider added later the choice
function setOpenSignUp(open: boolean) {
  void act('access', () => APP_BUILDER.setOpenSignUp(props.applicationId!, open), 'Could not change who can sign in.')
}

function setEnabled(provider: SignInProvider, enabled: boolean) {
  void act(provider.configuration.id!,
           () => saveSignInProvider({ ...provider, configuration: { ...provider.configuration, enabled } } as SignInProvider),
           'Could not change the provider.')
}

function remove(provider: SignInProvider) {
  const name = provider.type === 'PASSWORD' ? PASSWORD_PROVIDER_NAME : provider.configuration.name
  const question = provider.type === 'PASSWORD'
    ? `Delete ${name}? Sign-ins with a password stop at once.`
    : `Delete ${name}? Sign-ins through it stop at once, and its client secret is deleted.`
  if (window.confirm(question)) {
    void act(provider.configuration.id!, () => deleteSignInProvider(provider), 'Could not delete the provider.')
  }
}
</script>

<style scoped>
.intro {
  min-width: 0;
}

.panel--flush .alert {
  margin: 0 1.25rem 1rem;
}

.access {
  padding: 0 1.25rem 1.1rem;
}

.access__label {
  display: block;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-2);
}

.access .choices {
  max-width: 36rem;
}
</style>
