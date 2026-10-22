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
      <p v-if="app.description" class="page-subtitle">{{ app.description }}</p>

      <span class="stage__spacer" />

      <div v-if="activity" class="status glass" aria-live="polite">
        <span class="status__spinner"><span class="spinner" /></span>
        <span class="status__text">
          <span class="status__title">{{ activity }}</span>
          <span class="muted">{{ app.url ? 'Your app keeps running until the new version is live.' : `Your link appears here as soon as ${app.name} is live.` }}</span>
        </span>
      </div>

      <section v-else-if="app.url" class="status status--live glass">
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
             @activity="activity = $event"
             @deployed="markDeployed(app.applicationId, $event)"
             @close="chatOpen = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import AppChat from '@/components/AppChat.vue'
import AppIcon from '@/components/AppIcon.vue'
import { markDeployed, type BuilderApp } from '@/api/appBuilder'
import { copyText, hostOf } from '@/api/format'

/** Building an app: the chat in a drawer on the right, and what is happening to the app over the photo. */
const props = defineProps<{
  app: BuilderApp
}>()

const chatOpen = ref(true)
const activity = ref<string | null>(null)
const copied = ref(false)

const chip = computed(() => {
  let ret: { text: string; class: string }
  if (activity.value) {
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

.stage__spacer {
  flex: 1;
  min-height: 1.5rem;
}

.status {
  display: flex;
  align-items: center;
  gap: 0.9rem;
  max-width: 32.5rem;
  padding: 1rem 1.25rem;
  box-shadow: var(--shadow);
}

.status__spinner {
  display: inline-flex;
  color: var(--accent-link);
  font-size: 1.25rem;
}

.status__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  font-size: 0.875rem;
}

.status__title {
  font-size: 0.95rem;
  font-weight: 600;
}

.status--live {
  display: block;
  padding: 1.1rem 1.25rem;
}

.status__eyebrow {
  margin: 0;
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--success-text);
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
