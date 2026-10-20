<template>
  <div class="workspace">
    <header class="topbar">
      <BrandMark class="topbar__brand" />
      <span v-if="SESSION.organizationId" class="topbar__workspace" title="Your workspace id">{{ SESSION.organizationId }}</span>
      <span class="topbar__spacer" />
      <button type="button" class="btn btn--ghost" @click="handleSignOut">Sign out</button>
    </header>

    <AppChat v-if="selectedApp"
             :key="selectedApp.applicationId"
             :app="selectedApp"
             @back="closeApp"
             @deployed="markDeployed" />

    <main v-else class="home">
      <div class="home__inner">
        <div v-if="notice" class="alert alert--error" role="alert">{{ notice }}</div>

        <div v-if="loading" class="home__status">
          <span class="spinner" /> Loading your apps…
        </div>

        <div v-else-if="loadError" class="alert alert--error" role="alert">
          {{ loadError }}
          <button type="button" class="link-button" @click="load">Try again</button>
        </div>

        <section v-else-if="!githubLinked" class="welcome">
          <h1>Connect GitHub</h1>
          <p class="muted">Summit 42 keeps each app's code in a GitHub repository of your own. Install the Kinotic app on your GitHub account and you'll come straight back here.</p>
          <button type="button" class="btn" :disabled="connecting" @click="connectGitHub">
            <span v-if="connecting" class="spinner" />
            Continue to GitHub
          </button>
        </section>

        <template v-else>
          <section v-if="apps.length === 0" class="welcome welcome--compact">
            <h1>Name your first app</h1>
            <p class="muted">Give it a name, then describe what it should do. Summit 42 builds it, deploys it, and keeps changing it as you chat.</p>
          </section>

          <div v-else class="home__header">
            <h1>Your apps</h1>
            <button v-if="!creating" type="button" class="btn" @click="startCreating">New app</button>
          </div>

          <form v-if="creating || apps.length === 0" class="create" @submit.prevent="create">
            <div class="field">
              <label for="app-name">Name</label>
              <input id="app-name"
                     ref="nameInput"
                     v-model="newName"
                     class="input"
                     placeholder="e.g. Dog Grooming Bookings"
                     autocomplete="off"
                     required />
              <span class="hint">No two of your apps can share a name.</span>
            </div>
            <div class="field">
              <label for="app-description">What it's for <span class="muted">(optional)</span></label>
              <input id="app-description"
                     v-model="newDescription"
                     class="input"
                     placeholder="e.g. Customers book grooming appointments for their dogs"
                     autocomplete="off" />
            </div>
            <div class="create__actions">
              <button v-if="apps.length > 0" type="button" class="btn btn--secondary" :disabled="saving" @click="creating = false">
                Cancel
              </button>
              <button type="submit" class="btn" :disabled="saving || newName.trim().length === 0">
                <span v-if="saving" class="spinner" />
                {{ saving ? 'Setting up your app…' : 'Create app' }}
              </button>
            </div>
          </form>

          <ul v-if="apps.length > 0" class="apps">
            <li v-for="app in apps" :key="app.applicationId">
              <button type="button" class="app-card" @click="openApp(app)">
                <span class="app-card__name">{{ app.name }}</span>
                <span v-if="app.url" class="app-card__live">Live</span>
                <span class="app-card__description muted">{{ app.description || 'No description' }}</span>
              </button>
            </li>
          </ul>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppChat from '@/components/AppChat.vue'
import BrandMark from '@/components/BrandMark.vue'
import { APP_BUILDER, type BuilderApp } from '@/api/appBuilder'
import { GITHUB, GITHUB_CALLBACK_PATH } from '@/api/github'
import { SESSION, signOut } from '@/api/session'

const route = useRoute()
const router = useRouter()

const apps = ref<BuilderApp[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const notice = ref<string | null>(null)
const githubLinked = ref(false)
const connecting = ref(false)
const creating = ref(false)
const saving = ref(false)
const newName = ref('')
const newDescription = ref('')

const nameInput = ref<HTMLInputElement | null>(null)

// the open app is named by the ?app= query, so a reload or the back button keeps it
const selectedApp = computed(() => {
  const id = route.query.app
  return loading.value ? undefined : apps.value.find(app => app.applicationId === id)
})

onMounted(load)

function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const [linked, found] = await Promise.all([GITHUB.isLinked(), APP_BUILDER.findApps()])
    githubLinked.value = linked
    apps.value = found
  } catch (err) {
    loadError.value = errorText(err, 'Could not load your apps.')
  } finally {
    loading.value = false
  }
}

async function connectGitHub() {
  connecting.value = true
  notice.value = null
  try {
    window.location.assign(await GITHUB.startInstall(window.location.origin + GITHUB_CALLBACK_PATH))
  } catch (err) {
    notice.value = errorText(err, 'Could not start connecting GitHub.')
    connecting.value = false
  }
}

function startCreating() {
  creating.value = true
  void nextTick(() => nameInput.value?.focus())
}

async function create() {
  const name = newName.value.trim()
  if (!name || saving.value) return
  saving.value = true
  notice.value = null
  try {
    const app = await APP_BUILDER.createApp(name, newDescription.value.trim() || null)
    apps.value = [...apps.value, app].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
    newName.value = ''
    newDescription.value = ''
    creating.value = false
    openApp(app)
  } catch (err) {
    notice.value = errorText(err, 'Could not create the app.')
  } finally {
    saving.value = false
  }
}

function openApp(app: BuilderApp) {
  notice.value = null
  void router.push({ query: { app: app.applicationId } })
}

function markDeployed(url: string) {
  if (selectedApp.value) {
    selectedApp.value.url = url
  }
}

function closeApp() {
  void router.push({ query: {} })
}

async function handleSignOut() {
  await signOut().catch(() => {})
  await router.replace('/login')
}
</script>

<style scoped>
.workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  height: 100dvh;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 1rem;
  padding-top: max(0.6rem, env(safe-area-inset-top));
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}

.topbar__workspace {
  max-width: 14rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0.15rem 0.6rem;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--text-muted);
  font-family: var(--mono);
  font-size: 0.8rem;
}

.topbar__spacer {
  flex: 1;
}

.home {
  flex: 1;
  overflow-y: auto;
}

.home__inner {
  max-width: 48rem;
  margin: 0 auto;
  padding: 1.5rem 1rem 2rem;
}

.home__status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  padding: 3rem 0;
  color: var(--text-muted);
}

.welcome--compact {
  padding-top: 8vh;
}

.home__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin: 0.5rem 0 1.25rem;
}

.home__header h1 {
  margin: 0;
  font-size: 1.5rem;
  letter-spacing: -0.02em;
}

.create {
  max-width: 32rem;
  margin: 0 auto 1.5rem;
  padding: 1.25rem 1.25rem 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.create__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}

.apps {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
  gap: 0.75rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.app-card {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.3rem 0.75rem;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 1rem 1.1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.app-card:hover {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.app-card__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.app-card__live {
  padding: 0.1rem 0.55rem;
  border-radius: 999px;
  background: var(--success-soft);
  color: var(--success);
  font-size: 0.75rem;
  font-weight: 600;
}

.app-card__description {
  grid-column: 1 / span 2;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  font-size: 0.9rem;
}

@media (max-width: 560px) {
  .topbar {
    padding-left: 0.75rem;
    padding-right: 0.5rem;
    gap: 0.25rem;
  }

  .topbar__brand :deep(.brand__name) {
    display: none;
  }

  .topbar__workspace {
    max-width: 9rem;
  }

  .topbar .btn--ghost {
    padding: 0 0.55rem;
    font-size: 0.9rem;
  }

  .home__inner {
    padding: 1rem 0.75rem 1.5rem;
  }

  .create {
    padding: 1rem 0.9rem 0.75rem;
  }
}
</style>
