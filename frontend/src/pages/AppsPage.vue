<template>
  <main class="page">
    <div class="page__inner">
      <div v-if="loading" class="page__status"><span class="spinner" /> Loading your apps…</div>

      <div v-else-if="loadError" class="alert alert--error" role="alert">
        {{ loadError }}
        <button type="button" class="link-button" @click="load">Try again</button>
      </div>

      <section v-else-if="!githubLinked" class="onboard glass">
        <p class="eyebrow">Step 2 of 3</p>
        <h1 class="onboard__title">Connect GitHub</h1>
        <p class="muted">Summit 42 keeps each app's code in a GitHub repository of your own, so you always own what it builds.</p>
        <ol class="onboard__steps">
          <li><span>1</span>Install the Kinotic app on your GitHub account or organization</li>
          <li><span>2</span>Pick which repositories it may use, or let it create new ones</li>
          <li><span>3</span>You come straight back here to name your first app</li>
        </ol>
        <div v-if="connectError" class="alert alert--error" role="alert">{{ connectError }}</div>
        <button type="button" class="btn btn--block" :disabled="connecting" @click="connectGitHub">
          <span v-if="connecting" class="spinner" />
          Continue to GitHub
        </button>
      </section>

      <section v-else-if="APPS.list.length === 0" class="onboard glass">
        <p class="eyebrow">Step 3 of 3</p>
        <h1 class="onboard__title">Name your first app</h1>
        <p class="muted">Give it a name, then describe it in the chat. Summit 42 builds it, deploys it, and keeps changing it as you talk.</p>
        <CreateAppForm block @created="open" />
      </section>

      <template v-else>
        <div class="page__header">
          <div>
            <h1 class="page-title">Apps</h1>
            <p class="page-subtitle">Everything you've built. Open one to keep changing it.</p>
          </div>
          <button type="button" class="btn" @click="creating = true"><AppIcon name="plus" :stroke-width="2.25" /> New app</button>
        </div>

        <ul class="apps">
          <li v-for="app in APPS.list" :key="app.applicationId" class="app-card glass">
            <RouterLink :to="`/apps/${app.applicationId}`" class="app-card__main">
              <span class="app-card__top">
                <span class="app-card__name">{{ app.name }}</span>
                <span :class="['chip', { 'chip--live': app.url }]">{{ app.url ? 'Live' : 'New' }}</span>
              </span>
              <span class="app-card__description">{{ app.description || 'No description' }}</span>
            </RouterLink>
            <div class="app-card__foot">
              <a v-if="app.url" :href="app.url" target="_blank" rel="noopener" class="app-card__url">
                <span>{{ hostOf(app.url) }}</span><AppIcon name="external" />
              </a>
              <span v-else class="muted">Not deployed yet</span>
            </div>
          </li>
          <li>
            <button type="button" class="app-new" @click="creating = true">
              <AppIcon name="plus" />
              <span class="app-new__title">New app</span>
              <span class="muted">Name it, then describe it in the chat</span>
            </button>
          </li>
        </ul>
      </template>
    </div>

    <SideDrawer v-if="creating" title="New app" subtitle="Name it now. You'll describe what it does in the chat." @close="creating = false">
      <CreateAppForm @created="open">
        <template #cancel="{ saving }">
          <button type="button" class="btn btn--secondary" :disabled="saving" @click="creating = false">Cancel</button>
        </template>
      </CreateAppForm>
    </SideDrawer>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Kinotic } from '@kinotic-ai/core'
import AppIcon from '@/components/AppIcon.vue'
import CreateAppForm from '@/components/CreateAppForm.vue'
import SideDrawer from '@/components/SideDrawer.vue'
import { APPS, loadApps, type BuilderApp } from '@/api/appBuilder'
import { GITHUB_CALLBACK_PATH } from '@/api/github'
import { errorText, hostOf } from '@/api/format'

const router = useRouter()

const loading = ref(true)
const loadError = ref<string | null>(null)
const githubLinked = ref(false)
const connecting = ref(false)
const connectError = ref<string | null>(null)
const creating = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const [installation] = await Promise.all([Kinotic.githubAppInstallations.findForCurrentOrg(), loadApps()])
    githubLinked.value = installation != null
  } catch (err) {
    loadError.value = errorText(err, 'Could not load your apps.')
  } finally {
    loading.value = false
  }
}

async function connectGitHub() {
  connecting.value = true
  connectError.value = null
  try {
    window.location.assign(await Kinotic.githubAppInstallations.startInstall(window.location.origin + GITHUB_CALLBACK_PATH))
  } catch (err) {
    connectError.value = errorText(err, 'Could not start connecting GitHub.')
    connecting.value = false
  }
}

function open(app: BuilderApp) {
  creating.value = false
  void router.push(`/apps/${app.applicationId}`)
}
</script>

<style scoped>
.onboard {
  max-width: 32.5rem;
  margin: 8vh auto 0;
  padding: 1.9rem 1.9rem 1.75rem;
  box-shadow: var(--shadow);
}

.onboard__title {
  margin: 0.4rem 0 0.5rem;
  font-size: 1.75rem;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.onboard__steps {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin: 1.25rem 0 1.5rem;
  padding: 0;
  list-style: none;
}

.onboard__steps li {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
}

.onboard__steps span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-link);
  font-size: 0.8rem;
  font-weight: 600;
}

.apps {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
  gap: 1rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.app-card {
  display: flex;
  flex-direction: column;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.app-card:hover {
  border-color: var(--accent-border);
}

.app-card__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  padding: 1.1rem 1.25rem 0.9rem;
  color: var(--text);
}

.app-card__main:hover {
  color: var(--text);
}

.app-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.app-card__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 1.05rem;
  font-weight: 600;
}

.app-card__description {
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  color: var(--text-muted);
  font-size: 0.92rem;
}

.app-card__foot {
  margin: 0 1.25rem;
  padding: 0.7rem 0 0.9rem;
  border-top: 1px solid var(--border);
  font-size: 0.85rem;
}

.app-card__url {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  min-width: 0;
}

.app-card__url span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-card__url .icon {
  width: 0.85rem;
  height: 0.85rem;
}

.app-new {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  width: 100%;
  height: 100%;
  min-height: 9rem;
  padding: 1.25rem;
  border: 1px dashed rgba(255, 255, 255, 0.22);
  border-radius: var(--radius);
  background: rgba(10, 20, 32, 0.45);
  color: var(--text);
  font-size: 0.9rem;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.app-new:hover {
  border-color: var(--accent-border);
  background: rgba(10, 20, 32, 0.65);
}

.app-new .icon {
  width: 1.4rem;
  height: 1.4rem;
  color: var(--accent-link);
}

.app-new__title {
  font-weight: 600;
  font-size: 1rem;
}

@media (max-width: 560px) {
  .onboard {
    margin-top: 1rem;
    padding: 1.4rem 1.2rem;
  }
}
</style>
