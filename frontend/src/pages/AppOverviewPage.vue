<template>
  <main class="page">
    <div class="page__inner">
      <div class="page__header">
        <div>
          <h1 class="page-title">{{ app.name }}</h1>
          <p class="page-subtitle">{{ app.description || 'No description' }}</p>
        </div>
      </div>

      <section class="link glass">
        <span class="link__icon"><AppIcon name="globe" /></span>
        <div class="link__text">
          <p class="link__eyebrow">Your app's link</p>
          <template v-if="app.url">
            <a :href="app.url" target="_blank" rel="noopener" class="link__url">{{ app.url }}</a>
            <p class="link__hint muted">Share it with the people you invite. They sign in there to use the app.</p>
          </template>
          <template v-else>
            <p class="link__url link__url--pending">Not deployed yet</p>
            <p class="link__hint muted">Describe {{ app.name }} in the <RouterLink :to="`/apps/${app.applicationId}`">chat</RouterLink> and its link appears here once it's live.</p>
          </template>
        </div>
        <div v-if="app.url" class="link__actions">
          <button type="button" class="btn btn--secondary btn--small" @click="copyLink">
            <AppIcon :name="copied ? 'check' : 'copy'" /> {{ copied ? 'Copied' : 'Copy link' }}
          </button>
          <a :href="app.url" target="_blank" rel="noopener" class="btn btn--small">Open app <AppIcon name="external" /></a>
        </div>
      </section>

      <div v-if="loadError" class="alert alert--error" role="alert">
        {{ loadError }}
        <button type="button" class="link-button" @click="load">Try again</button>
      </div>

      <div class="stats">
        <div class="stat glass">
          <div class="stat__label">Status</div>
          <div class="stat__value"><span :class="['dot', `dot--${phase.kind}`]" />{{ loading ? '…' : phase.text }}</div>
          <div class="stat__note">{{ deployment?.updated ? `Changed ${timeAgo(deployment.updated)}` : 'Nothing deployed yet' }}</div>
        </div>
        <RouterLink :to="`/apps/${app.applicationId}/data`" class="stat glass stat--link">
          <div class="stat__label">Data</div>
          <div class="stat__value">{{ loading ? '…' : plural(typeCount, 'type') }}</div>
          <div class="stat__note">What {{ app.name }} stores</div>
        </RouterLink>
        <RouterLink :to="`/apps/${app.applicationId}/users`" class="stat glass stat--link">
          <div class="stat__label">Users</div>
          <div class="stat__value">{{ loading ? '…' : plural(userCount, 'user') }}</div>
          <div class="stat__note">{{ inviteCount > 0 ? `${plural(inviteCount, 'invitation')} pending` : 'People who can sign in' }}</div>
        </RouterLink>
        <div class="stat glass">
          <div class="stat__label">Code</div>
          <div class="stat__value">{{ project?.repoFullName ?? (loading ? '…' : '—') }}</div>
          <a v-if="project?.repoFullName" :href="repoUrl(project.repoFullName)" target="_blank" rel="noopener" class="stat__note stat__link">Open on GitHub <AppIcon name="external" /></a>
        </div>
      </div>

      <section class="panel panel--flush glass">
        <div class="panel__head">
          <h2 class="panel__title">Recent activity</h2>
          <RouterLink :to="`/apps/${app.applicationId}/deployments`">All deployments</RouterLink>
        </div>
        <ul v-if="history.length > 0" class="activity">
          <li v-for="(milestone, index) in history" :key="index">
            <span class="activity__message">{{ milestone.text }}</span>
            <span class="activity__when muted">{{ timeAgo(milestone.timestamp) }}</span>
          </li>
        </ul>
        <p v-else class="table__empty">{{ loading ? 'Loading…' : 'Nothing has been deployed yet.' }}</p>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Kinotic, Pageable } from '@kinotic-ai/core'
import type { Project, ProjectDeployment } from '@kinotic-ai/management-api'
import AppIcon from '@/components/AppIcon.vue'
import type { BuilderApp } from '@/api/appBuilder'
import { describeHistory, describePhase, findProjects, repoUrl, type DeploymentMilestone } from '@/api/appProjects'
import { copyText, errorText, timeAgo } from '@/api/format'

/** An app at a glance: its link, its status, and what changed lately. */
const props = defineProps<{
  app: BuilderApp
}>()

// each deployment writes about ten history entries, of which describeHistory keeps two or three
const RECENT_EVENTS = 50
const RECENT_MILESTONES = 5

const loading = ref(true)
const loadError = ref<string | null>(null)
const project = ref<Project | null>(null)
const deployment = ref<ProjectDeployment | null>(null)
const typeCount = ref(0)
const userCount = ref(0)
const inviteCount = ref(0)
const history = ref<DeploymentMilestone[]>([])
const copied = ref(false)

const phase = computed(() => describePhase(deployment.value?.state?.observed?.phase))

onMounted(load)

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

async function load() {
  loading.value = true
  loadError.value = null
  const applicationId = props.app.applicationId
  try {
    const [projects, types, users, invites] = await Promise.all([
      findProjects(applicationId),
      Kinotic.entityDefinitions.countForApplication(applicationId),
      Kinotic.members.findApplicationUsers(applicationId, Pageable.create(0, 1)),
      Kinotic.members.findPendingInvites(applicationId, Pageable.create(0, 1)),
    ])
    typeCount.value = types
    userCount.value = users.totalElements ?? 0
    inviteCount.value = invites.totalElements ?? 0
    project.value = projects[0] ?? null
    if (project.value?.id) {
      const [found, events] = await Promise.all([
        Kinotic.projects.findDeployment(project.value.id),
        Kinotic.projects.findDeploymentHistory(project.value.id, Pageable.create(0, RECENT_EVENTS)),
      ])
      deployment.value = found
      history.value = describeHistory(events.content ?? []).slice(0, RECENT_MILESTONES)
    }
  } catch (err) {
    loadError.value = errorText(err, `Could not load ${props.app.name}'s details.`)
  } finally {
    loading.value = false
  }
}

async function copyLink() {
  if (props.app.url && await copyText(props.app.url)) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}
</script>

<style scoped>
.link {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem 1.25rem;
  margin-bottom: 1.25rem;
  padding: 1.25rem 1.4rem;
  border-color: var(--accent-border);
}

.link__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 2.75rem;
  height: 2.75rem;
  border-radius: 12px;
  background: var(--accent-soft);
  color: var(--accent-link);
}

.link__text {
  flex: 1 1 18rem;
  min-width: 0;
}

.link__eyebrow {
  margin: 0;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.link__url {
  display: block;
  margin: 0.1rem 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--text);
}

.link__url--pending {
  color: var(--text-muted);
}

.link__hint {
  margin: 0;
  font-size: 0.85rem;
}

.link__actions {
  display: flex;
  gap: 0.5rem;
}

.stat--link {
  color: var(--text);
  transition: border-color 0.15s ease;
}

.stat--link:hover {
  border-color: var(--accent-border);
  color: var(--text);
}

.stat__link {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  color: var(--accent-link);
}

.stat__link .icon {
  width: 0.8rem;
  height: 0.8rem;
}

.activity {
  margin: 0;
  padding: 0;
  list-style: none;
}

.activity li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.75rem 1.4rem;
  border-top: 1px solid var(--border);
}

.activity__message {
  min-width: 0;
  overflow-wrap: anywhere;
}

.activity__when {
  flex: none;
  font-size: 0.85rem;
}
</style>
