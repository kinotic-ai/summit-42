<template>
  <AuthShell title="Welcome back" :subtitle="workspace ? `Sign in to the ${workspace} workspace.` : 'Sign in to keep building.'">
    <template #hero>
      <HeroPitch />
    </template>

    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <!-- the workspaces a GitHub sign-in found the account a member of; the person picks one -->
    <div v-if="choices.length > 0" class="choices">
      <p class="muted">Your account belongs to several workspaces. Choose one to sign in to.</p>
      <button v-for="choice in choices"
              :key="choice"
              type="button"
              class="btn btn--secondary btn--block"
              @click="selectWorkspace(choice)">
        {{ choice }}
      </button>
    </div>

    <ProviderButton v-else-if="workspace && workspaceGitHub"
                    :action="workspaceLoginUrl(workspace, workspaceGitHub.id, referer)"
                    :provider="GITHUB_PROVIDER"
                    label="GitHub"
                    verb="Continue" />

    <ProviderButton v-else-if="offered"
                    :action="socialLoginUrl(GITHUB_PROVIDER, referer)"
                    :provider="GITHUB_PROVIDER"
                    label="GitHub"
                    verb="Continue" />

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
  GITHUB_PROVIDER,
  type LoginProvider,
  socialLoginUrl,
  workspaceLoginUrl,
} from '@/api/auth'
import { SESSION } from '@/api/session'

const UNAVAILABLE = 'Signing in with GitHub is not available right now. Please try again later.'

const route = useRoute()
const router = useRouter()

const offered = ref(false)
const choices = ref<string[]>([])
const workspace = ref<string | null>(null)
const workspaceGitHub = ref<LoginProvider | null>(null)
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
  try {
    offered.value = (await fetchSocialProviders()).includes(GITHUB_PROVIDER)
  } catch {
    // an unreachable gateway leaves offered false, which shows UNAVAILABLE
  }
  if (!offered.value && choices.value.length === 0) {
    error.value ??= UNAVAILABLE
  }
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

// a workspace's sign-in lists the platform's GitHub registration, which signs the account in to that workspace
async function selectWorkspace(organizationId: string) {
  error.value = null
  try {
    const providers = await fetchWorkspaceProviders(organizationId)
    workspaceGitHub.value = providers?.find(provider => provider.provider === GITHUB_PROVIDER) ?? null
  } catch {
    workspaceGitHub.value = null
  }
  if (workspaceGitHub.value) {
    workspace.value = organizationId
    choices.value = []
  } else {
    error.value = UNAVAILABLE
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
</style>
