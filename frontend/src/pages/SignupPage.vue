<template>
  <AuthShell title="Create your account" subtitle="Your apps live in a workspace of your own.">
    <template #hero>
      <h1 class="hero__title">Describe an app.<br />Watch it go live.</h1>
      <p class="hero__text">Chat about what you need. Summit 42 builds it, deploys it, and keeps changing it as you talk.</p>
    </template>

    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <template v-if="sentTo">
      <div class="alert alert--success">
        We sent a link to <strong>{{ sentTo }}</strong>. Open it to name your workspace and set a password.
      </div>
      <button type="button" class="btn btn--secondary btn--block" @click="sentTo = null">Use a different email</button>
    </template>

    <template v-else>
      <ProviderButton v-for="provider in providers"
                      :key="provider"
                      :action="socialSignupUrl(provider)"
                      :provider="provider"
                      :label="providerDisplayName(provider)"
                      verb="Sign up" />
      <div v-if="providers.length > 0" class="divider">or</div>

      <form @submit.prevent="submit">
        <div class="field">
          <label for="name">Your name</label>
          <input id="name" v-model="displayName" class="input" autocomplete="name" required />
        </div>
        <div class="field">
          <label for="email">Email</label>
          <input id="email" v-model="email" class="input" type="email" autocomplete="email" required />
        </div>
        <button type="submit" class="btn btn--block" :disabled="sending">
          <span v-if="sending" class="spinner" />
          Continue with email
        </button>
      </form>
    </template>

    <template #footer>
      Already have an account? <RouterLink to="/login">Sign in</RouterLink>
    </template>
  </AuthShell>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AuthShell from '@/components/AuthShell.vue'
import ProviderButton from '@/components/ProviderButton.vue'
import { fetchSocialProviders, providerDisplayName, rememberSignupName, signUp, socialSignupUrl } from '@/api/auth'

const providers = ref<string[]>([])
const displayName = ref('')
const email = ref('')
const sending = ref(false)
const sentTo = ref<string | null>(null)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    providers.value = await fetchSocialProviders()
  } catch {
    // email sign-up still works without the provider buttons
  }
})

async function submit() {
  const name = displayName.value.trim()
  const address = email.value.trim()
  if (!name || !address) {
    error.value = 'Enter your name and email.'
    return
  }
  sending.value = true
  error.value = null
  try {
    await signUp(address, name)
    rememberSignupName(name)
    sentTo.value = address
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Sign-up failed'
  } finally {
    sending.value = false
  }
}
</script>

<style scoped>
.hero__title {
  margin: 0 0 0.75rem;
  font-size: clamp(2.2rem, 5vw, 3.25rem);
  font-weight: 700;
  line-height: 1.05;
  letter-spacing: -0.035em;
}

.hero__text {
  margin: 0;
  max-width: 28rem;
  font-size: 1.1rem;
  color: var(--text-2);
}

@media (max-width: 860px) {
  .hero__text {
    margin: 0 auto;
  }
}
</style>
