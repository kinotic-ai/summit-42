<template>
  <div class="app-chat">
    <div class="apphead">
      <div class="apphead__inner">
        <button type="button" class="btn btn--ghost apphead__back" aria-label="All apps" @click="emit('back')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 18l-6-6 6-6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <span class="apphead__back-label">Apps</span>
        </button>
        <span class="apphead__name" :title="app.name">{{ app.name }}</span>
        <span class="apphead__spacer" />
        <button type="button" class="btn btn--ghost" :disabled="streaming || resetting || loading" @click="startOver">
          <span v-if="resetting" class="spinner" />
          Start over
        </button>
      </div>
    </div>

    <main ref="scroller" class="chat">
      <div class="chat__inner">
        <div v-if="notice" class="alert alert--error" role="alert">{{ notice }}</div>

        <div v-if="loading" class="chat__status">
          <span class="spinner" /> Loading the conversation…
        </div>

        <div v-else-if="loadError" class="alert alert--error" role="alert">
          {{ loadError }}
          <button type="button" class="link-button" @click="load">Try again</button>
        </div>

        <section v-else-if="turns.length === 0" class="welcome">
          <h1>What should {{ app.name }} do?</h1>
          <p class="muted">Describe it in plain words. Summit 42 builds it, deploys it, and keeps changing it as you chat.</p>
          <div v-if="app.description" class="welcome__ideas">
            <button type="button" class="idea" @click="useIdea(app.description)">{{ app.description }}</button>
          </div>
        </section>

        <template v-else>
          <div v-for="(turn, index) in turns"
               :key="index"
               :class="['msg', turn.role === 'USER' ? 'msg--user' : 'msg--assistant']">
            <div v-if="turn.text" class="msg__bubble">{{ turn.text }}</div>
            <a v-if="turn.appUrl" :href="turn.appUrl" target="_blank" rel="noopener" class="ready">
              <span class="ready__title">Your app is live</span>
              <span class="ready__url">{{ turn.appUrl }}</span>
              <span class="ready__cta">Open ↗</span>
            </a>
            <div v-if="turn.error" class="msg__error" role="alert">{{ turn.error }}</div>
          </div>

          <div v-if="streaming" class="progress" aria-live="polite">
            <span class="spinner" />
            <span>{{ progress ?? 'Thinking…' }}</span>
          </div>
        </template>
      </div>
    </main>

    <footer class="composer">
      <div class="composer__inner">
        <a v-if="app.url" :href="app.url" target="_blank" rel="noopener" class="btn btn--success btn--block open-app">
          Open {{ app.name }} ↗
        </a>
        <form class="composer__form" @submit.prevent="send">
          <textarea ref="input"
                    v-model="draft"
                    rows="1"
                    class="composer__input"
                    :placeholder="turns.length === 0 ? `Describe what ${app.name} should do…` : 'Ask for a change…'"
                    :disabled="loading || loadError !== null"
                    aria-label="Message"
                    @keydown="onKeydown"
                    @input="autosize" />
          <button type="submit" class="btn composer__send" :disabled="!canSend" aria-label="Send">
            <span v-if="streaming" class="spinner" />
            <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </button>
        </form>
        <p class="composer__hint muted">Enter to send · Shift+Enter for a new line</p>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Subscription } from 'rxjs'
import { APP_BUILDER, type BuildEvent, type BuilderApp, type ChatRole, type Conversation } from '@/api/appBuilder'

/** One message as the chat shows it: an assistant turn also carries what failed and the app it made live. */
interface Turn {
  role: ChatRole
  text: string
  error: string | null
  appUrl: string | null
}

/** The chat with the builder about one app; the parent remounts it for another app. */
const props = defineProps<{
  app: BuilderApp
}>()

const emit = defineEmits<{
  /** The user asked for the list of apps. */
  back: []
  /** The app was deployed and its UI serves at the url. */
  deployed: [url: string]
}>()

const turns = ref<Turn[]>([])
const draft = ref('')
const loading = ref(true)
const loadError = ref<string | null>(null)
const notice = ref<string | null>(null)
const streaming = ref(false)
const progress = ref<string | null>(null)
const resetting = ref(false)

const scroller = ref<HTMLElement | null>(null)
const input = ref<HTMLTextAreaElement | null>(null)

let subscription: Subscription | null = null

const canSend = computed(() => !streaming.value && !loading.value && loadError.value === null
                               && draft.value.trim().length > 0)

onMounted(load)

onBeforeUnmount(() => subscription?.unsubscribe())

function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback
}

function show(conversation: Conversation) {
  turns.value = (conversation.messages ?? []).map(message => ({ role: message.role, text: message.text, error: null, appUrl: null }))
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    show(await APP_BUILDER.findConversation(props.app.applicationId))
  } catch (err) {
    loadError.value = errorText(err, 'Could not load the conversation.')
  } finally {
    loading.value = false
  }
  await scrollToBottom(true)
  input.value?.focus()
}

function send() {
  const message = draft.value.trim()
  if (!canSend.value || !message) return

  draft.value = ''
  notice.value = null
  void nextTick(autosize)
  turns.value.push({ role: 'USER', text: message, error: null, appUrl: null })
  turns.value.push({ role: 'ASSISTANT', text: '', error: null, appUrl: null })
  // the pushed object is wrapped in a reactive proxy, which the stream's updates must go through to render
  const reply = turns.value[turns.value.length - 1] as Turn
  streaming.value = true
  progress.value = null
  void scrollToBottom(true)

  subscription = APP_BUILDER.chat(props.app.applicationId, message).subscribe({
    next: (event: BuildEvent) => {
      apply(reply, event)
      void scrollToBottom(false)
    },
    error: (err: unknown) => {
      reply.error = errorText(err, 'The builder stopped unexpectedly.')
      finishTurn(reply)
    },
    complete: () => finishTurn(reply),
  })
}

function apply(reply: Turn, event: BuildEvent) {
  switch (event.type) {
    case 'TEXT':
      reply.text += event.text ?? ''
      break
    case 'PROGRESS':
      progress.value = event.text
      break
    case 'APP_READY':
      reply.appUrl = event.url
      if (event.url) {
        emit('deployed', event.url)
      }
      break
    case 'ERROR':
      reply.error = event.text || 'Something went wrong.'
      break
  }
}

function finishTurn(reply: Turn) {
  streaming.value = false
  progress.value = null
  subscription = null
  if (!reply.text && !reply.error && !reply.appUrl) {
    turns.value = turns.value.filter(turn => turn !== reply)
  }
  void nextTick(() => input.value?.focus())
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}

function autosize() {
  const el = input.value
  if (el) {
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 200) + 'px'
  }
}

function useIdea(idea: string) {
  draft.value = idea
  void nextTick(() => {
    autosize()
    input.value?.focus()
  })
}

// follows new content only while the reader is already at the bottom, so scrolling back through history is not interrupted
async function scrollToBottom(force: boolean) {
  const el = scroller.value
  const nearBottom = el !== null && el.scrollHeight - el.scrollTop - el.clientHeight < 120
  await nextTick()
  if (el && (force || nearBottom)) {
    el.scrollTop = el.scrollHeight
  }
}

async function startOver() {
  if (!window.confirm(`Start over? This clears the conversation about ${props.app.name}; the app itself is kept.`)) return
  resetting.value = true
  notice.value = null
  try {
    await APP_BUILDER.resetConversation(props.app.applicationId)
    await load()
  } catch (err) {
    notice.value = errorText(err, 'Could not start over.')
  } finally {
    resetting.value = false
  }
}
</script>

<style scoped>
.app-chat {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.apphead {
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}

.apphead__inner {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  max-width: 48rem;
  margin: 0 auto;
  padding: 0.4rem 1rem;
}

.apphead__back {
  gap: 0.25rem;
  padding-left: 0.4rem;
}

.apphead__back svg {
  width: 1.1rem;
  height: 1.1rem;
}

.apphead__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.apphead__spacer {
  flex: 1;
}

.chat {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.chat__inner {
  max-width: 48rem;
  margin: 0 auto;
  padding: 1.5rem 1rem 1rem;
}

.chat__status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  padding: 3rem 0;
  color: var(--text-muted);
}

.welcome__ideas {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.6rem;
}

.idea {
  max-width: 100%;
  padding: 0.55rem 0.9rem;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  font-size: 0.9rem;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.idea:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.msg {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.msg--user {
  align-items: flex-end;
}

.msg--assistant {
  align-items: flex-start;
}

.msg__bubble {
  max-width: min(85%, 40rem);
  padding: 0.7rem 0.95rem;
  border-radius: 16px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.msg--user .msg__bubble {
  background: var(--user-bubble);
  color: var(--user-text);
  border-bottom-right-radius: 4px;
}

.msg--assistant .msg__bubble {
  background: var(--surface);
  border: 1px solid var(--border);
  border-bottom-left-radius: 4px;
}

.msg__error {
  max-width: min(85%, 40rem);
  padding: 0.6rem 0.9rem;
  border-radius: 12px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 0.92rem;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.ready {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.1rem 1rem;
  align-items: center;
  width: min(85%, 26rem);
  padding: 0.8rem 1rem;
  border: 1px solid var(--success);
  border-radius: 14px;
  background: var(--success-soft);
  color: var(--text);
}

.ready:hover {
  text-decoration: none;
  filter: brightness(0.98);
}

.ready__title {
  font-weight: 700;
  color: var(--success);
}

.ready__url {
  grid-column: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.85rem;
  color: var(--text-muted);
}

.ready__cta {
  grid-column: 2;
  grid-row: 1 / span 2;
  font-weight: 600;
  color: var(--success);
}

.progress {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.85rem;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 0.9rem;
}

.composer {
  border-top: 1px solid var(--border);
  background: var(--surface);
  padding-bottom: env(safe-area-inset-bottom);
}

.composer__inner {
  max-width: 48rem;
  margin: 0 auto;
  padding: 0.75rem 1rem 0.5rem;
}

.open-app {
  margin-bottom: 0.65rem;
}

.composer__form {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
  padding: 0.4rem 0.4rem 0.4rem 0.9rem;
  border: 1px solid var(--border);
  border-radius: 16px;
  background: var(--bg);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.composer__form:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.composer__input {
  flex: 1;
  min-height: 2.5rem;
  max-height: 200px;
  padding: 0.5rem 0;
  border: none;
  background: transparent;
  outline: none;
  resize: none;
  line-height: 1.5;
}

.composer__send {
  width: 2.5rem;
  min-height: 2.5rem;
  padding: 0;
  border-radius: 12px;
}

.composer__send svg {
  width: 1.2rem;
  height: 1.2rem;
}

.composer__hint {
  margin: 0.4rem 0 0;
  font-size: 0.75rem;
  text-align: center;
}

@media (max-width: 560px) {
  .apphead__inner {
    padding: 0.35rem 0.5rem;
    gap: 0.25rem;
  }

  .apphead__back-label {
    display: none;
  }

  .apphead .btn--ghost {
    padding: 0 0.55rem;
    font-size: 0.9rem;
  }

  .chat__inner {
    padding: 1rem 0.75rem;
  }

  .msg__bubble,
  .msg__error {
    max-width: 92%;
  }

  .ready {
    width: 92%;
  }

  .composer__inner {
    padding: 0.6rem 0.75rem 0.4rem;
  }

  .composer__hint {
    display: none;
  }
}
</style>
