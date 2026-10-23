<template>
  <div class="shell photo-bg organization">
    <TopBar has-nav @toggle-nav="navOpen = !navOpen" />
    <div class="shell__body">
      <SideNav label="Organization" :items="NAV_ITEMS" :open="navOpen" @close="navOpen = false">
        <template #header>
          <div class="sidenav__wide organization__label">
            <p class="organization__eyebrow">Organization</p>
            <p class="organization__id">{{ SESSION.organizationId }}</p>
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

/** The pages about the whole organization: its apps, its members and its settings. */
const NAV_ITEMS: SideNavItem[] = [
  { label: 'Apps', to: '/', icon: 'apps' },
  { label: 'Members', to: '/members', icon: 'members' },
  { label: 'Settings', to: '/settings', icon: 'settings' },
]

const navOpen = ref(false)
</script>

<style scoped>
.organization {
  --photo-x: 72%;
}

.organization__label {
  padding: 0.25rem 0.75rem 1rem;
}

.organization__eyebrow {
  margin: 0;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-subtle);
}

.organization__id {
  margin: 0.15rem 0 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
</style>
