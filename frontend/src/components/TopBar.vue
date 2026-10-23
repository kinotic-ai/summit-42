<template>
  <header class="topbar">
    <button v-if="hasNav" type="button" class="icon-btn topbar__menu" aria-label="Menu" @click="emit('toggleNav')">
      <AppIcon name="menu" />
    </button>
    <RouterLink to="/" class="topbar__brand"><BrandMark /></RouterLink>
    <template v-if="SESSION.organizationId">
      <span class="topbar__sep topbar__crumb" aria-hidden="true">/</span>
      <RouterLink to="/" class="topbar__organization topbar__crumb">{{ SESSION.organizationId }}</RouterLink>
    </template>
    <template v-if="appName">
      <span class="topbar__sep" aria-hidden="true">/</span>
      <span class="topbar__app">{{ appName }}</span>
    </template>
    <span class="topbar__spacer" />
    <slot name="actions" />
    <div ref="account" class="account">
      <button type="button"
              class="account__button"
              :aria-label="`Account: ${SESSION.displayName ?? 'you'}`"
              :aria-expanded="menuOpen"
              @click="menuOpen = !menuOpen">
        {{ initials }}
      </button>
      <div v-if="menuOpen" class="account__menu glass" role="menu">
        <div v-if="SESSION.displayName" class="account__name">{{ SESSION.displayName }}</div>
        <div v-if="SESSION.organizationId" class="account__organization muted">{{ SESSION.organizationId }}</div>
        <button type="button" class="btn btn--ghost account__item" role="menuitem" @click="handleSignOut">Sign out</button>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import BrandMark from '@/components/BrandMark.vue'
import { initialsOf } from '@/api/format'
import { SESSION, signOut } from '@/api/session'

/** The bar across the top of every signed-in page: the brand, where the page is, and the account menu. */
defineProps<{
  /** The open app's name, shown after the organization. */
  appName?: string
  /** True when the page has a sidebar, which the menu button opens on a phone. */
  hasNav?: boolean
}>()

const emit = defineEmits<{
  /** The menu button was pressed. */
  toggleNav: []
}>()

const router = useRouter()
const menuOpen = ref(false)
const account = ref<HTMLElement | null>(null)

const initials = computed(() => initialsOf(SESSION.displayName))

function closeOnOutsideClick(event: MouseEvent) {
  if (menuOpen.value && account.value && !account.value.contains(event.target as Node)) {
    menuOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', closeOnOutsideClick))
onBeforeUnmount(() => document.removeEventListener('click', closeOnOutsideClick))

async function handleSignOut() {
  menuOpen.value = false
  await signOut().catch(() => {})
  await router.replace('/login')
}
</script>

<style scoped>
.topbar {
  position: relative;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex: none;
  height: 3.5rem;
  padding: 0 1rem 0 1.25rem;
  padding-top: env(safe-area-inset-top);
  border-bottom: 1px solid var(--border);
  background: var(--chrome);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

.topbar__menu {
  display: none;
  margin-left: -0.5rem;
}

.topbar__brand,
.topbar__brand:hover {
  color: var(--text);
}

.topbar__sep {
  color: #3e5266;
}

.topbar__organization {
  overflow: hidden;
  max-width: 14rem;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.9rem;
  color: var(--text-muted);
}

.topbar__organization:hover {
  color: var(--text);
}

.topbar__app {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.9rem;
  font-weight: 500;
}

.topbar__spacer {
  flex: 1;
}

.account {
  position: relative;
}

.account__button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.25rem;
  height: 2.25rem;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  background: var(--raised);
  color: var(--text);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
}

.account__menu {
  position: absolute;
  top: calc(100% + 0.5rem);
  right: 0;
  min-width: 14rem;
  padding: 0.75rem 0.5rem 0.5rem;
  background: var(--panel-solid);
  box-shadow: var(--shadow);
}

.account__name {
  padding: 0 0.6rem;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.account__organization {
  padding: 0 0.6rem 0.5rem;
  font-size: 0.85rem;
  overflow-wrap: anywhere;
}

.account__item {
  width: 100%;
  justify-content: flex-start;
}

@media (max-width: 560px) {
  .topbar {
    gap: 0.5rem;
    padding: 0 0.75rem;
  }

  .topbar__menu {
    display: inline-flex;
  }

  .topbar__crumb {
    display: none;
  }

  .topbar__brand :deep(.brand__name) {
    display: none;
  }
}
</style>
