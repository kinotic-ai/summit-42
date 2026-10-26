<template>
  <AuthShell title="Welcome back" subtitle="Sign in to keep building.">
    <template #hero>
      <HeroPitch />
    </template>

    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <!-- the organizations a GitHub sign-in found the account a member of; the person picks one -->
    <div v-if="choices.length > 0" class="choices">
      <p class="muted">Your account belongs to several organizations. Choose one to sign in to.</p>
      <button v-for="choice in choices"
              :key="choice"
              type="button"
              class="btn btn--secondary btn--block"
              @click="continueTo(choice)">
        {{ choice }}
      </button>
      <div class="divider">or</div>
    </div>

    <template v-if="githubOffered">
      <ProviderButton :action="socialLoginUrl(GITHUB_PROVIDER, referer)"
                      :provider="GITHUB_PROVIDER"
                      :label="providerDisplayName(GITHUB_PROVIDER)"
                      verb="Continue" />
      <div class="divider">or</div>
    </template>

    <!-- every other way to sign in is the organization's own, so its page lists them -->
    <form @submit.prevent="continueToTyped">
      <div class="field">
        <label for="organization">Organization</label>
        <input id="organization"
               v-model="organization"
               class="input"
               autocapitalize="none"
               autocorrect="off"
               spellcheck="false"
               placeholder="e.g. janes-apps" />
        <span class="hint">Its name or ID, for signing in with your organization's identity provider or a password.</span>
      </div>
      <button type="submit" class="btn btn--secondary btn--block">Continue</button>
    </form>

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
  authErrorOf,
  fetchOrganizationChoices,
  fetchSocialProviders,
  GITHUB_PROVIDER,
  providerDisplayName,
  refererOf,
  rememberedOrganization,
  socialLoginUrl,
  toOrganizationId,
} from '@/api/auth'
import { SESSION } from '@/api/session'

const route = useRoute()
const router = useRouter()

const githubOffered = ref(false)
const choices = ref<string[]>([])
const organization = ref(rememberedOrganization() ?? '')
const error = ref<string | null>(null)

// the page the router guard sent the visitor here from, where a sign-in returns to
const referer = computed(() => refererOf(route.query.referer))

onMounted(async () => {
  if (SESSION.connected) {
    await router.replace(referer.value ?? '/')
    return
  }
  const remembered = rememberedOrganization()
  // a browser that signed in before goes straight to its organization's page, unless a sign-in just failed here
  if (remembered && typeof route.query.error !== 'string') {
    await router.replace(organizationPage(remembered))
    return
  }
  await consumeError()
  try {
    githubOffered.value = (await fetchSocialProviders()).includes(GITHUB_PROVIDER)
  } catch {
    // an organization's own page still signs in without the GitHub button
  }
})

// the gateway's redirects land here with ?error=<code>; the error is cleared so a refresh does not repeat it, and the
// referer is kept for the sign-in started next
async function consumeError() {
  const failed = authErrorOf(route.query)
  if (failed) {
    await router.replace({ query: referer.value ? { referer: referer.value } : {} })
    if (failed.code === 'multiple_organizations') {
      try {
        choices.value = await fetchOrganizationChoices()
      } catch {
        choices.value = []
      }
    }
    if (choices.value.length === 0) {
      error.value = authErrorMessage(failed.code, failed.provider)
    }
  }
}

function continueToTyped() {
  const id = toOrganizationId(organization.value)
  if (id) {
    continueTo(id)
  } else {
    error.value = 'Enter your organization\'s name or ID.'
  }
}

function continueTo(organizationId: string) {
  void router.push(organizationPage(organizationId))
}

function organizationPage(organizationId: string) {
  return { path: `/login/${encodeURIComponent(organizationId)}`, query: referer.value ? { referer: referer.value } : {} }
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
