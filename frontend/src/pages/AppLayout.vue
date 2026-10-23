<template>
  <div class="shell photo-bg app-layout">
    <TopBar :app-name="app?.name" has-nav @toggle-nav="navOpen = !navOpen">
      <template #actions>
        <RouterLink v-if="app && route.name !== 'build'" :to="base" class="btn btn--outline btn--small">
          <AppIcon name="chat" /> Chat
        </RouterLink>
      </template>
    </TopBar>
    <div class="shell__body">
      <SideNav label="App" :items="items" :open="navOpen" @close="navOpen = false">
        <template #header>
          <RouterLink to="/" class="app-nav__back" title="All apps" @click="navOpen = false">
            <AppIcon name="back" :stroke-width="2" /><span class="sidenav__wide">All apps</span>
          </RouterLink>
          <div v-if="app" class="sidenav__wide app-nav__card">
            <div class="app-nav__top">
              <span class="app-nav__name">{{ app.name }}</span>
              <span :class="['chip', { 'chip--live': app.url }]">{{ app.url ? 'Live' : 'New' }}</span>
            </div>
            <a v-if="app.url" :href="app.url" target="_blank" rel="noopener" class="app-nav__url">
              <span>{{ hostOf(app.url) }}</span><AppIcon name="external" />
            </a>
            <div v-else class="app-nav__pending">Not deployed yet</div>
          </div>
        </template>
      </SideNav>

      <main v-if="loading" class="page"><div class="page__status"><span class="spinner" /> Loading…</div></main>
      <main v-else-if="loadError" class="page">
        <div class="alert alert--error" role="alert">
          {{ loadError }}
          <button type="button" class="link-button" @click="load">Try again</button>
        </div>
      </main>
      <main v-else-if="!app" class="page">
        <h1 class="page-title">App not found</h1>
        <p class="page-subtitle">This organization has no app with that id. <RouterLink to="/">See all apps</RouterLink></p>
      </main>
      <RouterView v-else v-slot="{ Component }">
        <component :is="Component" :app="app" />
      </RouterView>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import SideNav, { type SideNavItem } from '@/components/SideNav.vue'
import TopBar from '@/components/TopBar.vue'
import { APPS, ensureAppsLoaded } from '@/api/appBuilder'
import { errorText, hostOf } from '@/api/format'

/** The pages about one app, named by the route's applicationId: building it in the chat, and managing it. */
const route = useRoute()

const loading = ref(true)
const loadError = ref<string | null>(null)
const navOpen = ref(false)

const applicationId = computed(() => String(route.params.applicationId))
const app = computed(() => APPS.list.find(listed => listed.applicationId === applicationId.value))
const base = computed(() => `/apps/${encodeURIComponent(applicationId.value)}`)

const items = computed<SideNavItem[]>(() => [
  { label: 'Build', to: base.value, icon: 'chat' },
  { label: 'Overview', to: `${base.value}/overview`, icon: 'apps' },
  { label: 'Data', to: `${base.value}/data`, icon: 'data' },
  { label: 'Users', to: `${base.value}/users`, icon: 'members' },
  { label: 'Deployments', to: `${base.value}/deployments`, icon: 'deployments' },
  { label: 'Settings', to: `${base.value}/settings`, icon: 'settings' },
])

onMounted(load)

// the layout stays mounted when the user moves to another app, so the list is checked again for it
watch(applicationId, () => {
  if (!app.value) {
    void load()
  }
})

async function load() {
  loading.value = true
  loadError.value = null
  try {
    await ensureAppsLoaded()
  } catch (err) {
    loadError.value = errorText(err, 'Could not load your apps.')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.app-layout {
  --photo-x: 62%;
}

.app-nav__back {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  min-height: 2.25rem;
  padding: 0 0.75rem;
  color: var(--text-muted);
  font-size: 0.9rem;
}

.app-nav__back:hover {
  color: var(--text);
}

.app-nav__back .icon {
  width: 1rem;
  height: 1rem;
}

.app-nav__card {
  margin: 0.4rem 0 0.9rem;
  padding: 0.85rem;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
}

.app-nav__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.app-nav__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}

.app-nav__url {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  margin-top: 0.35rem;
  font-size: 0.8rem;
}

.app-nav__url span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-nav__url .icon {
  width: 0.8rem;
  height: 0.8rem;
}

.app-nav__pending {
  margin-top: 0.35rem;
  font-size: 0.8rem;
  color: var(--text-muted);
}

@media (max-width: 1180px) and (min-width: 561px) {
  .app-nav__back {
    justify-content: center;
    padding: 0;
  }
}
</style>
