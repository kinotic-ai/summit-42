<template>
  <svg class="icon"
       viewBox="0 0 24 24"
       aria-hidden="true"
       fill="none"
       stroke="currentColor"
       :stroke-width="strokeWidth"
       stroke-linecap="round"
       stroke-linejoin="round"
       v-html="PATHS[name]" />
</template>

<script setup lang="ts">
type IconName = 'apps' | 'back' | 'chat' | 'check' | 'close' | 'copy' | 'data' | 'deployments' | 'external' | 'globe' | 'menu' | 'members' | 'plus' | 'send' | 'settings' | 'warning'

/** The icons the UI draws, each as the inner markup of a 24×24 stroked SVG. */
const PATHS: Record<IconName, string> = {
  apps: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  back: '<path d="M15 18l-6-6 6-6"/>',
  chat: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  close: '<path d="M18 6L6 18M6 6l12 12"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>',
  data: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
  deployments: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
  external: '<path d="M7 17L17 7M8 7h9v9"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  members: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  send: '<path d="M12 19V5M5 12l7-7 7 7"/>',
  settings: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>',
  warning: '<path d="M12 9v4M12 17h.01"/><path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/>',
}

withDefaults(defineProps<{
  name: IconName
  strokeWidth?: number
}>(), {
  strokeWidth: 1.75,
})
</script>

<style scoped>
.icon {
  width: 1.125rem;
  height: 1.125rem;
  flex: none;
}
</style>
