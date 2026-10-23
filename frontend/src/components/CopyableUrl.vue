<template>
  <div class="url">
    <span class="url__text mono">{{ url ?? 'Loading…' }}</span>
    <button type="button" class="btn btn--secondary btn--small" :disabled="!url" @click="copy">
      <AppIcon :name="copied ? 'check' : 'copy'" /> {{ copied ? 'Copied' : 'Copy' }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { copyText } from '@/api/format'

/** A URL to give someone else, such as a provider's callback, with a button that copies it. */
const props = defineProps<{
  /** The URL, or null while it loads. */
  url: string | null
}>()

const copied = ref(false)

async function copy() {
  if (props.url && await copyText(props.url)) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}
</script>

<style scoped>
.url {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.5rem 0.5rem 0.8rem;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--input-bg);
}

.url__text {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--text-2);
}
</style>
