<template>
  <Teleport to="body">
    <div class="drawer-mask" aria-hidden="true" @click="emit('close')" />
    <aside ref="panel" class="drawer" role="dialog" aria-modal="true" :aria-label="title">
      <header class="drawer__head">
        <div class="drawer__heading">
          <h2 class="drawer__title">{{ title }}</h2>
          <p v-if="subtitle" class="drawer__subtitle">{{ subtitle }}</p>
        </div>
        <button type="button" class="icon-btn" aria-label="Close" @click="emit('close')"><AppIcon name="close" /></button>
      </header>
      <div class="drawer__body">
        <slot />
      </div>
      <footer v-if="$slots.footer" class="drawer__foot">
        <slot name="footer" />
      </footer>
    </aside>
  </Teleport>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

/** A panel that slides over the right of the page for a short form, such as inviting someone. */
defineProps<{
  title: string
  subtitle?: string
}>()

const emit = defineEmits<{
  /** The close button, the page behind, or Escape was pressed. */
  close: []
}>()

const panel = ref<HTMLElement | null>(null)

function closeOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    emit('close')
  }
}

onMounted(() => {
  document.addEventListener('keydown', closeOnEscape)
  panel.value?.querySelector<HTMLElement>('.drawer__body input, .drawer__body textarea, .drawer__body select')?.focus()
})

onBeforeUnmount(() => document.removeEventListener('keydown', closeOnEscape))
</script>

<style scoped>
.drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(4, 8, 14, 0.6);
}

.drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 41;
  display: flex;
  flex-direction: column;
  width: min(27.5rem, 100vw);
  background: var(--panel-solid);
  border-left: 1px solid var(--border);
  box-shadow: -12px 0 32px rgba(0, 0, 0, 0.35);
}

.drawer__head {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 1rem 0.9rem 1rem 1.25rem;
  padding-top: max(1rem, env(safe-area-inset-top));
  border-bottom: 1px solid var(--border);
}

.drawer__heading {
  flex: 1;
  min-width: 0;
}

.drawer__title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
}

.drawer__subtitle {
  margin: 0.15rem 0 0;
  font-size: 0.875rem;
  color: var(--text-muted);
}

.drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: 1.25rem;
}

.drawer__foot {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  padding: 0.9rem 1.25rem;
  padding-bottom: max(0.9rem, env(safe-area-inset-bottom));
  border-top: 1px solid var(--border);
}
</style>
