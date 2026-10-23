<template>
  <AuthShell title="Name your organization" :subtitle="subtitle">
    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

    <template v-if="token">
      <form @submit.prevent="submit">
        <div class="field">
          <label for="organization-name">Organization name</label>
          <input id="organization-name"
                 v-model="organizationName"
                 class="input"
                 placeholder="e.g. Jane's apps"
                 autocomplete="organization"
                 required />
          <span class="hint">Your apps live here. The name must not be taken.</span>
        </div>
        <slot />
        <button type="submit" class="btn btn--block" :disabled="creating">
          <span v-if="creating" class="spinner" />
          Start building
        </button>
      </form>
    </template>

    <template v-else>
      <p class="muted">This link is missing its sign-up token, or it has already been used.</p>
      <RouterLink to="/signup" class="btn btn--secondary btn--block">Sign up again</RouterLink>
    </template>

    <template #footer>
      Already have an account? <RouterLink to="/login">Sign in</RouterLink>
    </template>
  </AuthShell>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/components/AuthShell.vue'
import { connect } from '@/api/session'

/**
 * The last step of a sign-up, reached from the link the gateway sent: names the organization and
 * calls `create` with the sign-up token and that name. Once it resolves the session exists, so the page
 * connects and opens the organization.
 */
const props = defineProps<{
  subtitle: string
  initialName?: string
  create: (token: string, organizationName: string) => Promise<void>
}>()

const route = useRoute()
const router = useRouter()

const token = computed(() => typeof route.query.token === 'string' ? route.query.token : '')
const organizationName = ref(props.initialName ?? '')
const creating = ref(false)
const error = ref<string | null>(null)

async function submit() {
  const name = organizationName.value.trim()
  if (!name) {
    error.value = 'Name your organization.'
    return
  }
  creating.value = true
  error.value = null
  try {
    await props.create(token.value, name)
    if (await connect()) {
      await router.replace('/')
    } else {
      error.value = 'Your organization was created, but this browser did not keep the session. Sign in to continue.'
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Could not create your organization'
  } finally {
    creating.value = false
  }
}
</script>
