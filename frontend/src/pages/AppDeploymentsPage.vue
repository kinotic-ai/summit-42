<template>
  <main class="page">
    <div class="page__inner">
      <div class="page__header">
        <div>
          <h1 class="page-title">Deployments</h1>
          <p class="page-subtitle">Every change from the chat is committed to GitHub and deployed here.</p>
        </div>
        <button type="button" class="btn btn--secondary btn--small" :disabled="loading" @click="load">
          <span v-if="loading" class="spinner" /> Refresh
        </button>
      </div>

      <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>

      <div class="stats">
        <div class="stat glass">
          <div class="stat__label">Status</div>
          <div class="stat__value"><span :class="['dot', `dot--${phase.kind}`]" />{{ loading && !deployment ? '…' : phase.text }}</div>
          <div class="stat__note">{{ deployment?.updated ? `Since ${timeAgo(deployment.updated)}` : 'Nothing deployed yet' }}</div>
        </div>
        <div class="stat glass">
          <div class="stat__label">Live version</div>
          <div class="stat__value mono">{{ shortSha(liveSha) }}</div>
          <a v-if="liveSha && project?.repoFullName" :href="`${repoUrl(project.repoFullName)}/commit/${liveSha}`" target="_blank" rel="noopener" class="stat__note">View on GitHub</a>
          <div v-else class="stat__note">{{ deployingSha ? `Deploying ${shortSha(deployingSha)}` : 'No version is live' }}</div>
        </div>
        <div class="stat glass">
          <div class="stat__label">Pages</div>
          <div class="stat__value">{{ uis.length }} published</div>
          <div class="stat__note">{{ uis.map(ui => ui.name).join(', ') || 'None yet' }}</div>
        </div>
      </div>

      <div v-if="deployment?.failureMessage && phase.kind === 'danger'" class="alert alert--error" role="alert">
        <strong>The last deployment failed.</strong> {{ deployment.failureMessage }}
        Ask the chat to fix it.
      </div>

      <section v-if="uis.length > 0" class="panel panel--flush glass">
        <div class="panel__head"><h2 class="panel__title">Pages</h2></div>
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>Page</th><th>Status</th><th>Link</th></tr></thead>
            <tbody>
              <tr v-for="ui in uis" :key="ui.id!">
                <td>{{ ui.name }}<span v-if="ui.observation" class="table__sub">{{ ui.observation }}</span></td>
                <td><span :class="['chip', `chip--${describePhase(ui.state?.observed?.phase).kind}`]">{{ describePhase(ui.state?.observed?.phase).text }}</span></td>
                <td><a :href="ui.url" target="_blank" rel="noopener">{{ hostOf(ui.url) }}</a></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel panel--flush glass">
        <div class="panel__head"><h2 class="panel__title">History</h2></div>
        <div class="table-wrap">
          <table v-if="history.length > 0" class="table">
            <thead><tr><th>What happened</th><th>When</th></tr></thead>
            <tbody>
              <tr v-for="(event, index) in history" :key="index">
                <td>{{ event.message }}</td>
                <td class="table__when">{{ timeAgo(event['@timestamp']) }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="table__empty">{{ loading ? 'Loading…' : 'Nothing has been deployed yet.' }}</p>
        </div>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Kinotic, Pageable } from '@kinotic-ai/core'
import { DeploymentStatusType, type Project, type ProjectDeployment, type UiDeployment, type WatchEvent } from '@kinotic-ai/management-api'
import type { BuilderApp } from '@/api/appBuilder'
import { describePhase, findProjects, repoUrl } from '@/api/appProjects'
import { errorText, hostOf, timeAgo } from '@/api/format'

/** Where an app's deployment stands, the pages it publishes, and what happened to it. */
const props = defineProps<{
  app: BuilderApp
}>()

const HISTORY_SIZE = 30

const loading = ref(true)
const error = ref<string | null>(null)
const project = ref<Project | null>(null)
const deployment = ref<ProjectDeployment | null>(null)
const uis = ref<UiDeployment[]>([])
const history = ref<WatchEvent[]>([])

const phase = computed(() => describePhase(deployment.value?.state?.observed?.phase))
const liveSha = computed(() => deployment.value?.state?.observed?.commitSha ?? null)
const deployingSha = computed(() => {
  const state = deployment.value?.state
  return state?.observed?.phase === DeploymentStatusType.DEPLOYING ? state.desired?.commitSha ?? null : null
})

onMounted(load)

function shortSha(sha: string | null): string {
  return sha ? sha.slice(0, 7) : '—'
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const [projects, foundUis] = await Promise.all([
      findProjects(props.app.applicationId),
      Kinotic.uiDeployments.findAllForApplication(props.app.applicationId),
    ])
    uis.value = foundUis
    project.value = projects[0] ?? null
    if (project.value?.id) {
      const [found, events] = await Promise.all([
        Kinotic.projects.findDeployment(project.value.id),
        Kinotic.projects.findDeploymentHistory(project.value.id, Pageable.create(0, HISTORY_SIZE)),
      ])
      deployment.value = found
      history.value = events.content ?? []
    }
  } catch (err) {
    error.value = errorText(err, 'Could not load the deployments.')
  } finally {
    loading.value = false
  }
}
</script>
