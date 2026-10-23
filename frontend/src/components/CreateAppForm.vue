<template>
  <form class="create" @submit.prevent="create">
    <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>
    <div class="field">
      <label for="app-name">Name</label>
      <input id="app-name"
             v-model="name"
             class="input"
             placeholder="e.g. Dog Grooming Bookings"
             autocomplete="off"
             required />
      <span class="hint">No two of your apps can share a name.</span>
    </div>
    <div class="field">
      <label for="app-description">What should it do? <span class="muted">(optional)</span></label>
      <textarea id="app-description"
                v-model="description"
                class="input"
                rows="4"
                placeholder="e.g. Customers book grooming appointments for their dogs, and staff see the day's bookings" />
      <span class="hint">Summit 42 starts building from this as soon as the app is created.</span>
    </div>
    <div class="create__actions">
      <slot name="cancel" :saving="saving" />
      <button type="submit" :class="['btn', { 'btn--block': block }]" :disabled="saving || name.trim().length === 0">
        <span v-if="saving" class="spinner" />
        {{ saving ? 'Setting up your app…' : 'Create app' }}
      </button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { createApp, type BuilderApp } from '@/api/appBuilder'

/** Names and describes a new app and creates it in the organization. */
defineProps<{
  /** True to stretch the submit button across the form. */
  block?: boolean
}>()

const emit = defineEmits<{
  /** The app was created. */
  created: [app: BuilderApp]
}>()

const name = ref('')
const description = ref('')
const saving = ref(false)
const error = ref<string | null>(null)

async function create() {
  const trimmed = name.value.trim()
  if (!trimmed || saving.value) return
  saving.value = true
  error.value = null
  try {
    emit('created', await createApp(trimmed, description.value.trim() || null))
  } catch (err) {
    error.value = err instanceof Error && err.message ? err.message : 'Could not create the app.'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.create__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
</style>
