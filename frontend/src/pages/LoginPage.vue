<template>
  <AuthShell title="Welcome back" :subtitle="workspace ? `Sign in to the ${workspace} workspace.` : 'Sign in to keep building.'">
    <template #hero>
      <HeroPitch />
    </template>

    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <!-- the workspaces a social sign-in found the account a member of; the person picks one -->
    <div v-if="choices.length > 0" class="choices">
      <p class="muted">Your account belongs to several workspaces. Choose one to sign in to.</p>
      <button v-for="choice in choices"
              :key="choice"
              type="button"
              class="btn btn--secondary btn--block"
              @click="selectWorkspace(choice)">
        {{ choice }}
      </button>
      <div class="divider">or</div>
    </div>

    <template v-if="workspace">
      <ProviderButton v-for="provider in workspaceProviders"
                      :key="provider.id"
                      :action="workspaceLoginUrl(workspace, provider.id, referer)"
                      :provider="provider.provider"
                      :label="provider.name || providerDisplayName(provider.provider)"
                      verb="Continue" />
      <div v-if="workspaceProviders.length > 0" class="divider">or</div>

      <form @submit.prevent="signIn">
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
      <p class="switch muted">
        Not your workspace?
        <button type="button" class="link-button" @click="forgetWorkspace">Use another</button>
      </p>
    </template>

    <template v-else>
      <ProviderButton v-for="provider in socialProviders"
                      :key="provider"
                      :action="socialLoginUrl(provider, referer)"
                      :provider="provider"
                      :label="providerDisplayName(provider)"
                      verb="Continue" />
      <div v-if="socialProviders.length > 0" class="divider">or</div>

      <form @submit.prevent="useWorkspace">
        <div class="field">
          <label for="workspace">Workspace id</label>
          <input id="workspace"
                 v-model="workspaceInput"
                 class="input"
                 autocapitalize="none"
                 autocorrect="off"
                 spellcheck="false"
                 placeholder="e.g. janes-apps" />
          <span class="hint">Joined a workspace with an email and password? Enter its name or id.</span>
        </div>
        <button type="submit" class="btn btn--secondary btn--block">Continue with email</button>
      </form>
    </template>

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
  authErrorMessage,
  fetchSocialProviders,
  fetchWorkspaceChoices,
  fetchWorkspaceProviders,
  type LoginProvider,
  loginWithPassword,
  providerDisplayName,
  rememberedWorkspace,
  rememberWorkspace,
  socialLoginUrl,
  workspaceLoginUrl,
} from '@/api/auth'
import { connect, SESSION } from '@/api/session'

const route = useRoute()
const router = useRouter()

const workspace = ref<string | null>(rememberedWorkspace())
const workspaceInput = ref('')
const workspaceProviders = ref<LoginProvider[]>([])
const socialProviders = ref<string[]>([])
const choices = ref<string[]>([])
const email = ref('')
const password = ref('')
const signingIn = ref(false)
const error = ref<string | null>(null)

// the page the router guard sent the visitor here from, where a sign-in returns to; only a path within this UI
const referer = computed(() => {
  const value = route.query.referer
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
    ? value
    : null
})

onMounted(async () => {
  if (SESSION.connected) {
    await router.replace(referer.value ?? '/')
    return
  }
  await consumeError()
  await loadProviders()
})

// the gateway's redirects land here with ?error=<code>; the error is cleared so a refresh does not repeat it, and the
// referer is kept for the sign-in started next
async function consumeError() {
  const code = typeof route.query.error === 'string' ? route.query.error : null
  if (code) {
    await router.replace({ query: referer.value ? { referer: referer.value } : {} })
    if (code === 'multiple_organizations') {
      try {
        choices.value = await fetchWorkspaceChoices()
      } catch {
        choices.value = []
      }
    }
    if (choices.value.length === 0) {
      error.value = authErrorMessage(code)
    }
  }
}

async function loadProviders() {
  try {
    if (workspace.value) {
      const offered = await fetchWorkspaceProviders(workspace.value)
      if (offered === null) {
        error.value = `There is no workspace with the id "${workspace.value}".`
        workspace.value = null
        rememberWorkspace(null)
      } else {
        workspaceProviders.value = offered
      }
    }
    if (!workspace.value) {
      socialProviders.value = await fetchSocialProviders()
    }
  } catch {
    // the password form still works without the provider buttons
  }
}

function selectWorkspace(organizationId: string) {
  choices.value = []
  error.value = null
  workspace.value = organizationId
  void loadProviders()
}

function useWorkspace() {
  const id = toWorkspaceId(workspaceInput.value)
  if (!id) {
    error.value = 'Enter your workspace name or id.'
    return
  }
  selectWorkspace(id)
}

// a workspace id is its name slugified, so the name the user signed up with finds it too
function toWorkspaceId(input: string): string {
  return input.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/[^a-z0-9_]+/g, '-').replace(/^-+|-+$/g, '')
}

function forgetWorkspace() {
  workspace.value = null
  workspaceProviders.value = []
  rememberWorkspace(null)
  void loadProviders()
}

async function signIn() {
  if (!workspace.value) return
  signingIn.value = true
  error.value = null
  try {
    await loginWithPassword(workspace.value, email.value.trim(), password.value)
    rememberWorkspace(workspace.value)
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
.choices {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

.choices p {
  margin: 0 0 0.25rem;
}

.switch {
  margin: 1rem 0 0;
  text-align: center;
  font-size: 0.9rem;
}
</style>
