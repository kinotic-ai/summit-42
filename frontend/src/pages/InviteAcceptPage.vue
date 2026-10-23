<template>
  <AuthShell :title="title" :subtitle="subtitle">
    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <div v-if="loading" class="loading"><span class="spinner" /></div>

    <div v-else-if="accepted" class="alert alert--success" role="status">
      Your account is ready. The app has no sign-in page yet, so ask whoever invited you where to open it.
    </div>

    <template v-else-if="invite">
      <!-- a workspace member builds in the workspace's GitHub, so a workspace invitation is accepted with GitHub -->
      <template v-if="!invite.applicationId">
        <ProviderButton v-if="inviteGitHub"
                        :action="inviteProviderUrl(inviteGitHub.id, token!)"
                        :provider="GITHUB_PROVIDER"
                        label="GitHub"
                        verb="Accept" />
        <p v-else class="muted">Accepting with GitHub is not available right now. Please try again later.</p>
      </template>

      <form v-else-if="passwordAccepts" @submit.prevent="accept">
        <div class="field">
          <label for="email">Email</label>
          <input id="email" class="input" type="email" :value="invite.email" readonly />
        </div>
        <template v-if="existingPassword">
          <div class="field">
            <label for="password">Password</label>
            <input id="password" v-model="password" class="input" type="password" autocomplete="current-password" required />
            <span class="hint">You already have an account. Enter its password to join.</span>
          </div>
        </template>
        <template v-else>
          <div class="field">
            <label for="name">Your name</label>
            <input id="name" v-model="displayName" class="input" autocomplete="name" />
          </div>
          <div class="field">
            <label for="password">Password</label>
            <input id="password" v-model="password" class="input" type="password" autocomplete="new-password" required />
          </div>
          <div class="field">
            <label for="confirm">Confirm password</label>
            <input id="confirm" v-model="confirm" class="input" type="password" autocomplete="new-password" required />
          </div>
        </template>
        <button type="submit" class="btn btn--block" :disabled="accepting">
          <span v-if="accepting" class="spinner" />
          Accept invitation
        </button>
      </form>
      <p v-else class="muted">Your account signs in through a provider. Accept with it above.</p>
    </template>

    <template #footer>
      Already a member? <RouterLink to="/login">Sign in</RouterLink>
    </template>
  </AuthShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/components/AuthShell.vue'
import ProviderButton from '@/components/ProviderButton.vue'
import {
  acceptInvite,
  authErrorMessage,
  fetchInvite,
  GITHUB_PROVIDER,
  type InviteDetails,
  inviteProviderUrl,
} from '@/api/auth'

const route = useRoute()
const router = useRouter()

const token = typeof route.query.token === 'string' && route.query.token ? route.query.token : null
const invite = ref<InviteDetails | null>(null)
const loading = ref(true)
const accepting = ref(false)
const accepted = ref(false)
const error = ref<string | null>(null)
const displayName = ref('')
const password = ref('')
const confirm = ref('')

const title = computed(() => {
  const workspace = invite.value?.organizationName
  let ret = 'Your invitation'
  if (invite.value?.applicationId) {
    ret = workspace ? `Join an app from ${workspace}` : 'Join an app'
  } else if (workspace) {
    ret = `Join ${workspace}`
  }
  return ret
})

const subtitle = computed(() => {
  let ret: string | undefined
  if (invite.value && !accepted.value) {
    const by = invite.value.invitedByName
    const to = invite.value.applicationId ? 'to use one of their apps' : 'to build apps with them on Summit 42'
    ret = by ? `${by} invited ${invite.value.email} ${to}.` : `${invite.value.email} is invited ${to}.`
  }
  return ret
})

const inviteGitHub = computed(() => invite.value?.providers.find(provider => provider.provider === GITHUB_PROVIDER) ?? null)

// an app invitation is accepted with a password: the existing account's password accepts; an account that only signs in through a provider accepts through it
const existingPassword = computed(() => !!invite.value?.organizationUserExists && invite.value.organizationUserHasPassword)
const passwordAccepts = computed(() => !invite.value?.organizationUserExists || existingPassword.value)

onMounted(async () => {
  // a failed provider acceptance lands here with ?error=<code>; it is cleared so a refresh does not repeat it
  const code = typeof route.query.error === 'string' ? route.query.error : null
  if (code) {
    error.value = authErrorMessage(code)
    await router.replace({ query: token ? { token } : {} })
  }
  if (!token) {
    error.value ??= 'This invitation link is incomplete. Open it from the email again.'
  } else {
    try {
      invite.value = await fetchInvite(token)
      displayName.value = invite.value.displayName ?? ''
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'This invitation could not be loaded.'
    }
  }
  loading.value = false
})

async function accept() {
  if (!token) return
  if (!existingPassword.value && password.value !== confirm.value) {
    error.value = 'The passwords do not match.'
    return
  }
  accepting.value = true
  error.value = null
  try {
    const app = await acceptInvite(token, password.value, displayName.value.trim() || null)
    if (app?.primaryUiUrl) {
      window.location.assign(app.primaryUiUrl)
    } else {
      accepted.value = true
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Could not accept the invitation'
    password.value = ''
    confirm.value = ''
  } finally {
    accepting.value = false
  }
}
</script>

<style scoped>
.loading {
  display: flex;
  justify-content: center;
  padding: 1.5rem 0;
}
</style>
