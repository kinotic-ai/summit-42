<template>
  <div class="shell photo-bg workspace">
    <TopBar has-nav @toggle-nav="navOpen = !navOpen" />
    <div class="shell__body">
      <SideNav label="Workspace" :items="NAV_ITEMS" :open="navOpen" @close="navOpen = false">
        <template #header>
          <div class="sidenav__wide workspace__label">
            <p class="workspace__eyebrow">Workspace</p>
            <p class="workspace__id">{{ SESSION.organizationId }}</p>
          </div>
        </template>
      </SideNav>
      <RouterView />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import SideNav, { type SideNavItem } from '@/components/SideNav.vue'
import TopBar from '@/components/TopBar.vue'
import { SESSION } from '@/api/session'

/** The pages about the whole workspace: its apps, its members and its settings. */
const NAV_ITEMS: SideNavItem[] = [
  { label: 'Apps', to: '/', icon: 'apps' },
  { label: 'Members', to: '/members', icon: 'members' },
  { label: 'Settings', to: '/settings', icon: 'settings' },
]

const navOpen = ref(false)
</script>

<style scoped>
.workspace {
  --photo-x: 72%;
}

.workspace__label {
  padding: 0.25rem 0.75rem 1rem;
}

.workspace__eyebrow {
  margin: 0;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-subtle);
}

.workspace__id {
  margin: 0.15rem 0 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
</style>
