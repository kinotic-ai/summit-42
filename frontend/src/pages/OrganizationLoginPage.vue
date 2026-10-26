<template>
  <AuthShell :title="`Sign in to ${organizationId}`" subtitle="Use any way your organization offers.">
    <template #hero>
      <HeroPitch />
    </template>

    <div v-if="error" class="alert alert--error" role="alert">
      {{ error }}
      <button v-if="providersUnavailable" type="button" class="link-button" @click="loadProviders">Try again</button>
    </div>

    <p v-if="unknown" class="muted">There is no organization named <strong>{{ organizationId }}</strong>.</p>

    <template v-else>
      <ProviderButton v-for="provider in oidcProviders"
                      :key="provider.id"
                      :action="organizationLoginUrl(organizationId, provider.id, referer)"
                      :provider="provider.provider"
                      :label="provider.name || providerDisplayName(provider.provider)"
                      verb="Continue" />
      <div v-if="oidcProviders.length > 0 && passwordOffered" class="divider">or</div>

      <!-- the login takes a password only while the organization offers one among its providers -->
      <form v-if="passwordOffered" @submit.prevent="signIn">
        <div class="field">
          <label for="email">Email</label>
          <input id="email" v-model="email" class="input" type="email" autocomplete="email" required />
        </div>
        <div class="field">
          <label for="password">Password</label>
          <input id="password" v-model="password" class="input" type="password" autocomplete="current-password" required />
        </div>
        <button type="submit" class="btn btn--block" :disabled="signingIn">
          <span v-if="signingIn" class="spinner" />
          Sign in
        </button>
      </form>
    </template>

    <p class="switch muted">
      Not your organization?
      <button type="button" class="link-button" @click="useAnother">Use another</button>
    </p>

    <template #footer>
      New to Summit 42? <RouterLink to="/signup">Create an account</RouterLink>
    </template>
  </AuthShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/components/AuthShell.vue'
import HeroPitch from '@/components/HeroPitch.vue'
import ProviderButton from '@/components/ProviderButton.vue'
import {
  fetchOrganizationProviders,
  forgetOrganization,
  type LoginProvider,
  loginWithPassword,
  organizationLoginUrl,
  providerDisplayName,
  refererOf,
  rememberedOrganization,
} from '@/api/auth'
import { connect, SESSION } from '@/api/session'

/** One organization's sign-in: the providers it offers, and its members' email and password while it takes one. */
const props = defineProps<{
  organizationId: string
}>()

const PROVIDERS_UNAVAILABLE = 'Could not load the ways to sign in to this organization. Refresh the page to try again.'

const route = useRoute()
const router = useRouter()

const providers = ref<LoginProvider[]>([])
const unknown = ref(false)
const providersUnavailable = ref(false)
const email = ref('')
const password = ref('')
const signingIn = ref(false)
const error = ref<string | null>(null)

const referer = computed(() => refererOf(route.query.referer))
const oidcProviders = computed(() => providers.value.filter(provider => provider.type === 'OIDC'))
const passwordOffered = computed(() => providers.value.some(provider => provider.type === 'PASSWORD'))

onMounted(async () => {
  if (SESSION.connected) {
    await router.replace(referer.value ?? '/')
    return
  }
  await loadProviders()
})

// the page offers only what the list says the organization offers, so a list that cannot be read is tried again
async function loadProviders() {
  error.value = null
  providersUnavailable.value = false
  try {
    const offered = await fetchOrganizationProviders(props.organizationId)
    unknown.value = offered === null
    providers.value = offered ?? []
    // a remembered organization that no longer exists would send every visit to /login back here
    if (unknown.value && props.organizationId === rememberedOrganization()) {
      forgetOrganization()
    }
  } catch {
    error.value = PROVIDERS_UNAVAILABLE
    providersUnavailable.value = true
  }
}

// the remembered organization would send /login straight back here
function useAnother() {
  forgetOrganization()
  void router.push({ path: '/login', query: referer.value ? { referer: referer.value } : {} })
}

async function signIn() {
  signingIn.value = true
  error.value = null
  try {
    await loginWithPassword(props.organizationId, email.value.trim(), password.value)
    if (await connect()) {
      await router.replace(referer.value ?? '/')
    } else {
      error.value = 'You were signed in, but this browser did not keep the session. Allow cookies for the Summit 42 server and try again.'
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Invalid credentials'
    password.value = ''
  } finally {
    signingIn.value = false
  }
}
</script>

<style scoped>
.switch {
  margin: 1rem 0 0;
  text-align: center;
  font-size: 0.9rem;
}
</style>
