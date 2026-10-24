<template>
  <div :class="['build', { 'build--chat': chatOpen }]">
    <section class="stage">
      <div class="stage__head">
        <div class="stage__title">
          <h1 class="page-title">{{ app.name }}</h1>
          <span :class="['chip', chip.class]">{{ chip.text }}</span>
        </div>
        <button v-if="!chatOpen" type="button" class="btn btn--outline btn--small" @click="chatOpen = true">
          <AppIcon name="chat" /> Chat
        </button>
      </div>
      <p v-if="app.description" class="page-subtitle stage__description" :title="app.description">{{ app.description }}</p>

      <span class="stage__spacer" />

      <section v-if="progress || !app.url" class="status glass">
        <div class="tracker__head">
          <p :class="['status__eyebrow', { 'status__eyebrow--idle': !progress }]">{{ trackerTitle }}</p>
          <span v-if="progress" class="tracker__elapsed">{{ formatDuration((progress.finishedAt ?? now) - progress.startedAt) }}</span>
        </div>
        <ol class="tracker">
          <li v-for="(stage, index) in BUILD_STAGES" :key="stage" :class="['tracker__stage', `tracker__stage--${stageState(index)}`]">
            <span class="tracker__track">
              <span class="tracker__dot"><AppIcon v-if="stageState(index) === 'done'" name="check" :stroke-width="3" /></span>
              <span v-if="index < BUILD_STAGES.length - 1" class="tracker__line" />
            </span>
            <span class="tracker__label">{{ STAGE_LABELS[stage] }}</span>
          </li>
        </ol>
        <p class="tracker__detail">{{ progress ? progress.detail : 'Describe it in the chat, and Summit 42 builds and deploys it.' }}</p>
      </section>

      <section v-else class="status glass">
        <p class="status__eyebrow">Your app is live</p>
        <a :href="app.url" target="_blank" rel="noopener" class="status__url">{{ hostOf(app.url) }}</a>
        <div class="status__actions">
          <a :href="app.url" target="_blank" rel="noopener" class="btn btn--small">Open app <AppIcon name="external" /></a>
          <button type="button" class="btn btn--secondary btn--small" @click="copyLink">
            <AppIcon :name="copied ? 'check' : 'copy'" /> {{ copied ? 'Copied' : 'Copy link' }}
          </button>
        </div>
      </section>
    </section>

    <AppChat v-if="chatOpen"
             :key="app.applicationId"
             class="build__chat"
             :app="app"
             @progress="progress = $event"
             @deployed="markDeployed(app.applicationId, $event)"
             @close="chatOpen = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppChat from '@/components/AppChat.vue'
import AppIcon from '@/components/AppIcon.vue'
import { BUILD_STAGES, markDeployed, type BuildProgress, type BuildStage, type BuilderApp } from '@/api/appBuilder'
import { copyText, formatDuration, hostOf } from '@/api/format'

/** Building an app: the chat in a drawer on the right, where the build's progress shows, and the app's link over the photo. */
const props = defineProps<{
  app: BuilderApp
}>()

const chatOpen = ref(true)
const progress = ref<BuildProgress | null>(null)
const copied = ref(false)
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | null = null

const STAGE_LABELS: Record<BuildStage, string> = {
  PLANNING: 'Planning',
  WRITING: 'Writing code',
  DEPLOYING: 'Deploying',
  LIVE: 'Live',
}

const trackerTitle = computed(() => {
  let ret = 'Not built yet'
  if (progress.value) {
    ret = props.app.url ? `Updating ${props.app.name}` : `Building ${props.app.name}`
  }
  return ret
})

// the elapsed time ticks only while a turn runs
watch(() => progress.value !== null, running => {
  stopClock()
  if (running) {
    now.value = Date.now()
    clock = setInterval(() => { now.value = Date.now() }, 1000)
  }
})

onBeforeUnmount(stopClock)

function stopClock() {
  if (clock !== null) {
    clearInterval(clock)
    clock = null
  }
}

// stages before the turn's are done, its own is current, and Live is done once reached
function stageState(index: number): 'done' | 'current' | 'todo' {
  const current = progress.value ? BUILD_STAGES.indexOf(progress.value.stage) : -1
  let ret: 'done' | 'current' | 'todo' = 'todo'
  if (index < current || (index === current && progress.value?.stage === 'LIVE')) {
    ret = 'done'
  } else if (index === current) {
    ret = 'current'
  }
  return ret
}

const chip = computed(() => {
  let ret: { text: string; class: string }
  if (progress.value) {
    ret = { text: props.app.url ? 'Updating' : 'Building', class: 'chip--working' }
  } else if (props.app.url) {
    ret = { text: 'Live', class: 'chip--live' }
  } else {
    ret = { text: 'Not built yet', class: '' }
  }
  return ret
})

async function copyLink() {
  if (props.app.url && await copyText(props.app.url)) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}
</script>

<style scoped>
.build {
  flex: 1;
  min-width: 0;
  display: flex;
}

.stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 1.75rem 2.25rem 2rem;
  overflow-y: auto;
}

.stage__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.stage__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.6rem 0.75rem;
  min-width: 0;
}

.stage__description {
  max-width: 48rem;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

.stage__spacer {
  flex: 1;
  min-height: 1.5rem;
}

.status {
  max-width: 32.5rem;
  padding: 1.1rem 1.25rem;
  box-shadow: var(--shadow);
}

.status__eyebrow {
  margin: 0;
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--success-text);
}

.status__eyebrow--idle {
  color: var(--text-muted);
}

.tracker__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
}

.tracker__elapsed {
  font-size: 0.82rem;
  font-variant-numeric: tabular-nums;
  color: var(--text-muted);
}

.tracker {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin: 0.9rem 0 0.8rem;
  padding: 0;
  list-style: none;
}

.tracker__stage {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
  min-width: 0;
}

.tracker__track {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  padding-right: 0.35rem;
}

.tracker__dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 1.25rem;
  height: 1.25rem;
  border: 2px solid var(--border-strong);
  border-radius: 50%;
}

.tracker__dot .icon {
  width: 0.7rem;
  height: 0.7rem;
}

.tracker__line {
  flex: 1;
  height: 2px;
  border-radius: 1px;
  background: var(--border-strong);
}

.tracker__label {
  font-size: 0.82rem;
  color: var(--text-subtle);
}

.tracker__stage--done .tracker__dot {
  border-color: var(--success);
  background: var(--success);
  color: var(--bg);
}

.tracker__stage--done .tracker__line {
  background: var(--success);
}

.tracker__stage--done .tracker__label {
  color: var(--text-muted);
}

.tracker__stage--current .tracker__dot {
  border-color: var(--accent);
  background: var(--accent);
  animation: tracker-pulse 1.6s ease-out infinite;
}

.tracker__stage--current .tracker__label {
  font-weight: 600;
  color: var(--text);
}

@keyframes tracker-pulse {
  0% { box-shadow: 0 0 0 0 rgba(232, 135, 90, 0.55); }
  100% { box-shadow: 0 0 0 0.6rem rgba(232, 135, 90, 0); }
}

@media (prefers-reduced-motion: reduce) {
  .tracker__stage--current .tracker__dot {
    animation: none;
  }
}

.tracker__detail {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 0.9rem;
  color: var(--text-2);
}

.status__url {
  display: block;
  margin: 0.25rem 0 0.9rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text);
}

.status__url:hover {
  color: var(--accent-link-hover);
}

.status__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.build__chat {
  flex: 0 0 26.25rem;
}

@media (max-width: 1180px) {
  .build__chat {
    flex-basis: 23.75rem;
  }
}

@media (max-width: 860px) {
  .build--chat .stage {
    display: none;
  }

  .build__chat {
    flex: 1 1 auto;
  }
}

@media (max-width: 560px) {
  .stage {
    padding: 1.25rem 1rem 1.5rem;
  }
}
</style>
