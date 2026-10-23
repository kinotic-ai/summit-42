<template>
  <div v-if="open" class="sidenav-mask" aria-hidden="true" @click="emit('close')" />
  <nav :aria-label="label" :class="['sidenav', { 'sidenav--open': open }]">
    <slot name="header" />
    <RouterLink v-for="item in items"
                :key="item.label"
                :to="item.to"
                :title="item.label"
                class="sidenav__item"
                exact-active-class="sidenav__item--active"
                @click="emit('close')">
      <AppIcon :name="item.icon" />
      <span class="sidenav__label">{{ item.label }}</span>
    </RouterLink>
  </nav>
</template>

<script lang="ts">
import type { RouteLocationRaw } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'

/** One link of the sidebar. */
export interface SideNavItem {
  label: string
  to: RouteLocationRaw
  icon: InstanceType<typeof AppIcon>['$props']['name']
}
</script>

<script setup lang="ts">

/**
 * The sidebar of a signed-in page. It narrows to icons on a small screen and, on a phone, opens over the page
 * while {@code open} is true.
 */
defineProps<{
  label: string
  items: SideNavItem[]
  open: boolean
}>()

const emit = defineEmits<{
  /** A link was followed or the page behind the open sidebar was pressed. */
  close: []
}>()
</script>

<style scoped>
.sidenav {
  flex: 0 0 14.5rem;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 1rem 0.75rem;
  border-right: 1px solid var(--border);
  background: rgba(20, 33, 49, 0.55);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  overflow-y: auto;
}

.sidenav__item {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  min-height: 2.5rem;
  padding: 0 0.75rem;
  border-radius: 8px;
  color: var(--text-nav);
  font-size: 0.95rem;
  font-weight: 500;
}

.sidenav__item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text);
}

.sidenav__item--active,
.sidenav__item--active:hover {
  background: var(--accent-soft);
  color: var(--accent-link);
}

.sidenav-mask {
  display: none;
}

@media (max-width: 1180px) {
  .sidenav {
    flex-basis: 4.25rem;
    padding: 1rem 0.6rem;
  }

  .sidenav__item {
    justify-content: center;
    padding: 0;
  }

  .sidenav__label {
    display: none;
  }
}

@media (max-width: 560px) {
  .sidenav {
    position: fixed;
    top: 3.5rem;
    bottom: 0;
    left: 0;
    z-index: 30;
    width: 16rem;
    padding: 1rem 0.75rem;
    background: var(--panel-solid);
    transform: translateX(-100%);
    transition: transform 0.2s ease;
  }

  .sidenav--open {
    transform: none;
    box-shadow: var(--shadow);
  }

  .sidenav__item {
    justify-content: flex-start;
    padding: 0 0.75rem;
  }

  .sidenav__label {
    display: revert;
  }

  .sidenav-mask {
    display: block;
    position: fixed;
    inset: 3.5rem 0 0;
    z-index: 25;
    background: rgba(4, 8, 14, 0.6);
  }
}
</style>
