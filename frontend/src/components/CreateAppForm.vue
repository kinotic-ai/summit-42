<template>
  <section v-if="saving" class="creating" aria-live="polite">
    <div class="creating__head">
      <h3 class="creating__title">Setting up {{ creatingName }}</h3>
      <span class="creating__elapsed">{{ formatDuration(now - startedAt) }}</span>
    </div>
    <ol class="steps">
      <li v-for="(step, index) in steps" :key="step.type" :class="['step', `step--${stepState(index)}`]">
        <span class="step__icon">
          <span v-if="stepState(index) === 'active'" class="spinner" />
          <AppIcon v-else-if="stepState(index) === 'done'" name="check" :stroke-width="3" />
        </span>
        <span class="step__text">{{ step.text }}</span>
      </li>
    </ol>
    <p class="hint">The chat opens as soon as your app is ready.</p>
  </section>

  <form v-else class="create" @submit.prevent="create">
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
      <slot name="cancel" />
      <button type="submit" :class="['btn', { 'btn--block': block }]" :disabled="name.trim().length === 0">Create app</button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { createApp, type AppCreationEventType, type BuilderApp } from '@/api/appBuilder'
import { errorText, formatDuration } from '@/api/format'

/** Names and describes a new app and creates it in the organization, showing each step while it does. */
defineProps<{
  /** True to stretch the submit button across the form. */
  block?: boolean
}>()

const emit = defineEmits<{
  /** The app was created. */
  created: [app: BuilderApp]
}>()

/** A step of creating the app, and the event that starts it; CREATED starts opening the app's chat. */
interface CreationStep {
  type: AppCreationEventType
  text: string
}

const name = ref('')
const description = ref('')
const saving = ref(false)
const error = ref<string | null>(null)
const creatingName = ref('')
const steps = ref<CreationStep[]>([])
// the index in steps of the step running, or -1 before the first starts
const current = ref(-1)
const startedAt = ref(0)
const now = ref(0)
let clock: ReturnType<typeof setInterval> | null = null

onBeforeUnmount(stopClock)

async function create() {
  const trimmed = name.value.trim()
  if (!trimmed || saving.value) return
  const wanted = description.value.trim() || null
  creatingName.value = trimmed
  steps.value = stepsFor(wanted !== null)
  current.value = -1
  error.value = null
  saving.value = true
  startClock()
  // the steps stay shown once the app is created, while the parent opens its chat
  try {
    emit('created', await createApp(trimmed, wanted, type => {
      current.value = steps.value.findIndex(step => step.type === type)
    }))
  } catch (err) {
    error.value = errorText(err, 'Could not create the app.')
    saving.value = false
    stopClock()
  }
}

// the steps of creating the app, in the order the server reports them; one with no description skips summarizing it
function stepsFor(described: boolean): CreationStep[] {
  const ret: CreationStep[] = []
  if (described) {
    ret.push({ type: 'DESCRIBING', text: 'Reading what you want it to do' })
  }
  ret.push({ type: 'CREATING_APPLICATION', text: 'Creating your app' })
  ret.push({ type: 'CREATING_REPOSITORY', text: 'Setting up its code on GitHub' })
  ret.push({ type: 'CREATED', text: 'Opening the chat' })
  return ret
}

function stepState(index: number): 'done' | 'active' | 'todo' {
  let ret: 'done' | 'active' | 'todo' = 'todo'
  if (index < current.value) {
    ret = 'done'
  } else if (index === current.value) {
    ret = 'active'
  }
  return ret
}

function startClock() {
  stopClock()
  startedAt.value = Date.now()
  now.value = startedAt.value
  clock = setInterval(() => { now.value = Date.now() }, 1000)
}

function stopClock() {
  if (clock !== null) {
    clearInterval(clock)
    clock = null
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

.creating {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
}

.creating__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
}

.creating__title {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 1.05rem;
  font-weight: 600;
}

.creating__elapsed {
  flex: none;
  font-size: 0.82rem;
  font-variant-numeric: tabular-nums;
  color: var(--text-muted);
}

.creating .steps {
  gap: 0.75rem;
  padding: 1rem 1.1rem;
  font-size: 0.92rem;
}

.creating .hint {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--text-subtle);
}
</style>
