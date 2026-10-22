<template>
  <AuthShell title="Connecting GitHub" :subtitle="error ? undefined : 'Finishing the link to your GitHub account…'">
    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>
    <button v-if="error" type="button" class="btn btn--secondary btn--block" @click="back">Back</button>
    <div v-else class="working muted"><span class="spinner" /> One moment</div>
  </AuthShell>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/components/AuthShell.vue'
import { Kinotic } from '@kinotic-ai/core'

/**
 * Where GitHub's install returns, through Kinotic, with installation_id, state and code in the query: finishes the
 * workspace's GitHub link and opens the workspace.
 */
const route = useRoute()
const router = useRouter()

const error = ref<string | null>(null)

function queryValue(name: string): string | null {
  const value = route.query[name]
  return typeof value === 'string' && value ? value : null
}

onMounted(async () => {
  const installationId = Number.parseInt(queryValue('installation_id') ?? '', 10)
  const state = queryValue('state')
  const code = queryValue('code')
  if (!Number.isFinite(installationId) || !state || !code) {
    error.value = 'GitHub did not return everything needed to finish the link. Please connect GitHub again.'
    return
  }
  try {
    await Kinotic.githubAppInstallations.completeInstall(installationId, state, code)
    await router.replace('/')
  } catch (err) {
    error.value = err instanceof Error && err.message ? err.message : 'Could not finish connecting GitHub.'
  }
})

function back() {
  void router.replace('/')
}
</script>

<style scoped>
.working {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  padding: 0.5rem 0;
}
</style>
