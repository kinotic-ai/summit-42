<template>
  <AuthShell :title="title" :subtitle="subtitle">
    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <div v-if="loading" class="loading"><span class="spinner" /></div>

    <div v-else-if="accepted" class="alert alert--success" role="status">
      Your account is ready. The app has no sign-in page yet, so ask whoever invited you where to open it.
    </div>

    <template v-else-if="invite">
      <!-- an app's providers return to the app, whose sign-in page accepts the invitation on the first sign-in -->
      <template v-if="invite.applicationId">
        <template v-if="invite.providers.length > 0">
          <p class="muted">
            Sign in to the app with {{ providerNames }} as <strong>{{ invite.email }}</strong>; your first sign-in accepts this
            invitation.
          </p>
          <a v-if="invite.signInUrl" :href="invite.signInUrl" class="btn btn--secondary btn--block">Continue to the app</a>
          <div v-if="invite.localEnabled" class="divider">or set a password</div>
        </template>
        <p v-else-if="!invite.localEnabled" class="muted">
          The app offers no way to sign in right now, so this invitation cannot be accepted yet. Ask whoever invited you
          to turn on a sign-in method for it, then open this link again.
        </p>
      </template>

      <!-- an invitation into the organization is accepted through any provider its login offers -->
      <template v-else>
        <ProviderButton v-for="provider in invite.providers"
                        :key="provider.id"
                        :action="inviteProviderUrl(provider.id, token!)"
                        :provider="provider.provider"
                        :label="provider.name || providerDisplayName(provider.provider)"
                        verb="Accept" />
        <div v-if="invite.providers.length > 0 && invite.localEnabled" class="divider">or set a password</div>
        <p v-if="!invite.localEnabled" class="muted">
          {{ invite.organizationName || 'The organization' }} accepts invitations through its sign-in providers. Continue with
          one of them as <strong>{{ invite.email }}</strong> to join.
        </p>
      </template>

      <form v-if="invite.localEnabled" @submit.prevent="accept">
        <div class="field">
          <label for="email">Email</label>
          <input id="email" class="input" type="email" :value="invite.email" readonly />
        </div>
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
        <button type="submit" class="btn btn--block" :disabled="accepting">
          <span v-if="accepting" class="spinner" />
          Accept invitation
        </button>
      </form>
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
  authErrorOf,
  fetchInvite,
  type InviteDetails,
  inviteProviderUrl,
  providerDisplayName,
} from '@/api/auth'

/**
 * The emailed invitation's page: an invitation into the organization is accepted through any provider its login
 * offers or, while the organization takes passwords, by setting one; an invitation into an app by setting a password
 * where the app takes one, or by signing in at the app through one of its providers.
 */
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
  const organization = invite.value?.organizationName
  let ret = 'Your invitation'
  if (invite.value?.applicationId) {
    ret = organization ? `Join an app from ${organization}` : 'Join an app'
  } else if (organization) {
    ret = `Join ${organization}`
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

// the app's providers by name, as its sign-in page shows them: "Google, Okta or Microsoft"
const providerNames = computed(() => {
  const names = (invite.value?.providers ?? []).map(provider => provider.name || providerDisplayName(provider.provider))
  return names.length > 1 ? `${names.slice(0, -1).join(', ')} or ${names[names.length - 1]}` : names.join('')
})

onMounted(async () => {
  // a failed provider acceptance lands here with ?error=<code>; it is cleared so a refresh does not repeat it
  const failed = authErrorOf(route.query)
  if (failed) {
    error.value = authErrorMessage(failed.code, failed.provider)
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
  if (password.value !== confirm.value) {
    error.value = 'The passwords do not match.'
    return
  }
  accepting.value = true
  error.value = null
  try {
    const app = await acceptInvite(token, password.value, displayName.value.trim() || null)
    if (app?.primaryUiUrl) {
      window.location.assign(app.primaryUiUrl)
    } else if (app) {
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

.muted + .btn {
  margin-top: 0.5rem;
}
</style>
