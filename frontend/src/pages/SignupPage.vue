<template>
  <AuthShell title="Create your account" subtitle="Your apps live in an organization of your own.">
    <template #hero>
      <HeroPitch />
    </template>

    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <!-- each organization builds its apps in its own GitHub account, so sign-up goes through GitHub -->
    <ProviderButton v-if="offered"
                    :action="socialSignupUrl(GITHUB_PROVIDER)"
                    :provider="GITHUB_PROVIDER"
                    label="GitHub"
                    verb="Sign up" />

    <template #footer>
      Already have an account? <RouterLink to="/login">Sign in</RouterLink>
    </template>
  </AuthShell>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AuthShell from '@/components/AuthShell.vue'
import HeroPitch from '@/components/HeroPitch.vue'
import ProviderButton from '@/components/ProviderButton.vue'
import { fetchSocialProviders, GITHUB_PROVIDER, socialSignupUrl } from '@/api/auth'

const UNAVAILABLE = 'Signing up with GitHub is not available right now. Please try again later.'

const offered = ref(false)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    offered.value = (await fetchSocialProviders()).includes(GITHUB_PROVIDER)
  } catch {
    // an unreachable gateway leaves offered false, which shows UNAVAILABLE
  }
  if (!offered.value) {
    error.value = UNAVAILABLE
  }
})
</script>
