<template>
  <aside class="chat" aria-label="Chat">
    <header class="chat__head">
      <div class="chat__heading">
        <h2 class="chat__title">Chat</h2>
        <p class="chat__subtitle">{{ app.url ? `Ask for a change and Summit 42 redeploys ${app.name}.` : `Tell Summit 42 what ${app.name} should do.` }}</p>
        <div :class="['chat__status', `chat__status--${status.kind}`]">
          <span v-if="status.kind === 'working'" class="spinner" />
          <AppIcon v-else-if="status.kind === 'live'" name="check" :stroke-width="2.5" />
          <a v-if="status.kind === 'live' && app.url" :href="app.url" target="_blank" rel="noopener">{{ status.text }}</a>
          <span v-else>{{ status.text }}</span>
        </div>
      </div>
      <button v-if="turns.length > 0" type="button" class="btn btn--ghost" :disabled="streaming || resetting || loading" @click="startOver">
        <span v-if="resetting" class="spinner" />
        Start over
      </button>
      <button type="button" class="icon-btn" aria-label="Close chat" @click="emit('close')"><AppIcon name="close" /></button>
    </header>

    <div ref="scroller" class="chat__messages" @scroll="onScroll">
      <div v-if="notice" class="alert alert--error" role="alert">{{ notice }}</div>

      <div v-if="loading" class="chat__loading"><span class="spinner" /> Loading the conversation…</div>

      <div v-else-if="loadError" class="alert alert--error" role="alert">
        {{ loadError }}
        <button type="button" class="link-button" @click="load">Try again</button>
      </div>

      <section v-else-if="turns.length === 0" class="chat__empty">
        <h3>What should {{ app.name }} do?</h3>
        <p class="muted">Describe it in plain words. Summit 42 builds it, deploys it, and keeps changing it as you chat.</p>
        <button v-if="app.description" type="button" class="idea" @click="useIdea(app.description)">{{ app.description }}</button>
      </section>

      <template v-else>
        <div v-for="(turn, index) in turns"
             :key="index"
             :class="['msg', turn.role === 'USER' ? 'msg--user' : 'msg--assistant']">
          <template v-for="(part, partIndex) in turn.parts" :key="partIndex">
            <div v-if="part.type === 'text'" class="msg__bubble">{{ part.text }}</div>
            <ul v-else class="steps" aria-label="What Summit 42 did" aria-live="polite">
              <li v-for="(step, stepIndex) in part.steps" :key="stepIndex" :class="['step', `step--${step.state}`]">
                <span class="step__icon">
                  <span v-if="step.state === 'active'" class="spinner" />
                  <AppIcon v-else-if="step.state === 'done'" name="check" :stroke-width="3" />
                  <AppIcon v-else name="close" :stroke-width="3" />
                </span>
                <span class="step__text">{{ step.text }}</span>
                <span v-if="step.state === 'active' && elapsed(step.startedAt) !== null" class="step__elapsed">{{ elapsed(step.startedAt) }}</span>
              </li>
            </ul>
          </template>
          <div v-if="turn === reply && thinking" class="thinking" aria-live="polite">
            <span class="spinner" /> Thinking
            <span v-if="elapsed(lastEventAt) !== null" class="thinking__elapsed">{{ elapsed(lastEventAt) }}</span>
          </div>
          <div v-if="turn.error" class="msg__error" role="alert">{{ turn.error }}</div>
        </div>
        <div v-for="(message, index) in queued" :key="`queued-${index}`" class="msg msg--user msg--queued">
          <div class="msg__bubble">{{ message }}</div>
          <div class="msg__note">Claude reads this after its current step</div>
        </div>
      </template>
    </div>

    <footer class="chat__composer">
      <form class="composer" @submit.prevent="send">
        <textarea ref="input"
                  v-model="draft"
                  rows="1"
                  class="composer__input"
                  :placeholder="placeholder"
                  :disabled="loading || loadError !== null"
                  aria-label="Message"
                  @keydown="onKeydown"
                  @input="autosize" />
        <button type="submit" class="btn composer__send" aria-label="Send">
          <AppIcon name="send" :stroke-width="2.25" />
        </button>
      </form>
      <p class="composer__hint">Enter to send · Shift+Enter for a new line</p>
    </footer>
  </aside>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Observable, Subscription } from 'rxjs'
import AppIcon from '@/components/AppIcon.vue'
import {
  APP_BUILDER,
  type BuildEvent,
  type BuilderApp,
  type BuildProgress,
  type BuildStage,
  type BuildStepType,
  type ChatRole,
  type Conversation,
  takeUnsentDescription,
} from '@/api/appBuilder'
import { errorText, formatDuration, hostOf } from '@/api/format'

/** One thing the builder did in a turn, as the PROGRESS events report it. */
interface Step {
  text: string
  state: 'active' | 'done' | 'failed'
  /** When the step started, in epoch milliseconds. */
  startedAt: number
  /** What the step does, or LIVE for the step that reports the app deployed. */
  type: BuildStepType | 'LIVE' | null
}

/** A run of the reply's text, or the steps taken between two runs of it. */
type Part = { type: 'text'; text: string } | { type: 'steps'; steps: Step[] }

/** One message as the chat shows it: an assistant turn interleaves its text with the steps it took, in order. */
interface Turn {
  role: ChatRole
  parts: Part[]
  error: string | null
}

// a step or a quiet stream shows how long it has run once it has run this long
const ELAPSED_SHOWN_AFTER_MS = 3000
// how close to the bottom a reader counts as there, so new output keeps them at the bottom
const FOLLOW_DISTANCE_PX = 48

/** The chat with the builder about one app; the parent remounts it for another app. */
const props = defineProps<{
  app: BuilderApp
}>()

const emit = defineEmits<{
  /** The app was deployed and its UI serves at the url. */
  deployed: [url: string]
  /** Where the running turn is, or null when no turn is running. */
  progress: [progress: BuildProgress | null]
  /** The user closed the chat. */
  close: []
}>()

const turns = ref<Turn[]>([])
const draft = ref('')
const loading = ref(true)
const loadError = ref<string | null>(null)
const notice = ref<string | null>(null)
const streaming = ref(false)
const resetting = ref(false)
// the reply the running turn streams into, the time of its last event, and a clock the elapsed times tick with
const reply = ref<Turn | null>(null)
/** Messages sent during the running turn that it has not read yet, in the order they were sent. */
const queued = ref<string[]>([])
const lastEventAt = ref(0)
// when the round of work the build card tracks started, and the round's last step that sets the stage
const roundStartedAt = ref(0)
const stageStep = ref<Step | null>(null)
const now = ref(Date.now())

const scroller = ref<HTMLElement | null>(null)
const input = ref<HTMLTextAreaElement | null>(null)

let subscription: Subscription | null = null
// the stream the reply follows; a stream the reply no longer follows changes nothing
let followed: object | null = null
let clock: ReturnType<typeof setInterval> | null = null
let contentObserver: MutationObserver | null = null
// true while the reader is at the bottom of the messages, where new output keeps them
let following = true

const placeholder = computed(() => {
  let ret = 'Ask for a change…'
  if (turns.value.length === 0) {
    ret = `Describe what ${props.app.name} should do…`
  } else if (streaming.value) {
    ret = 'Add to what Claude is doing…'
  }
  return ret
})

const canSend = computed(() => !loading.value && loadError.value === null && draft.value.trim().length > 0)

const activeStep = computed(() => (reply.value ? activeStepOf(reply.value) : null))

const currentStep = computed(() => activeStep.value?.text ?? null)

// Claude is working without a step to show: before its first words, between steps, or while it writes a tool call;
// shown once the stream has gone quiet, so it never trails text that is still streaming in
const thinking = computed(() => streaming.value && activeStep.value === null
                                && (reply.value?.parts.length === 0 || now.value - lastEventAt.value >= 1000))

// the stage follows the round's last step: reading plans, a quiet stretch after reading is Claude writing the code it
// then commits, and deploying runs until the app is live, which ends the round; checking the deployed app keeps the
// stage it is in
const progress = computed<BuildProgress | null>(() => {
  let ret: BuildProgress | null = null
  if (streaming.value && reply.value) {
    const last = stageStep.value
    let stage: BuildStage = 'PLANNING'
    if (last?.type === 'LIVE') {
      stage = 'LIVE'
    } else if (last?.type === 'DEPLOYING') {
      stage = 'DEPLOYING'
    } else if (last?.type === 'COMMITTING' || (last?.type === 'READING' && last.state !== 'active')) {
      stage = 'WRITING'
    }
    let detail = activeStep.value?.text ?? 'Thinking'
    if (stage === 'LIVE' && last && !activeStep.value) {
      detail = last.text
    } else if (stage === 'WRITING' && !activeStep.value) {
      detail = 'Writing the code'
    }
    ret = { stage, detail, startedAt: roundStartedAt.value, finishedAt: stage === 'LIVE' && last ? last.startedAt : null }
  }
  return ret
})

watch(progress, value => emit('progress', value))

// the one-line status the header shows when the chat fills the screen and the page behind it is hidden
const status = computed(() => {
  let ret: { kind: 'working' | 'live' | 'idle'; text: string }
  if (streaming.value) {
    ret = { kind: 'working', text: currentStep.value ?? 'Thinking' }
  } else if (props.app.url) {
    ret = { kind: 'live', text: `Live at ${hostOf(props.app.url)}` }
  } else {
    ret = { kind: 'idle', text: 'Not deployed yet' }
  }
  return ret
})

onMounted(() => {
  // every change to the messages, streamed text, a new step or the Thinking row alike, keeps a following reader at the bottom
  contentObserver = new MutationObserver(followNewContent)
  if (scroller.value) {
    contentObserver.observe(scroller.value, { childList: true, subtree: true, characterData: true })
  }
  void load()
})

onBeforeUnmount(() => {
  contentObserver?.disconnect()
  subscription?.unsubscribe()
  stopClock()
  emit('progress', null)
})

function show(conversation: Conversation) {
  turns.value = (conversation.messages ?? []).map(message => ({ role: message.role, parts: textParts(message.text), error: null }))
}

function textParts(text: string): Part[] {
  return text ? [{ type: 'text', text }] : []
}

/** How long ago a time was, such as 42s or 1:05, or null when it is too recent to show. */
function elapsed(since: number): string | null {
  const ms = now.value - since
  let ret: string | null = null
  if (ms >= ELAPSED_SHOWN_AFTER_MS) {
    ret = formatDuration(ms)
  }
  return ret
}

function stopClock() {
  if (clock !== null) {
    clearInterval(clock)
    clock = null
  }
}

// the conversation, then either the description of an app just created, sent as its first message, or the turn still
// running from an earlier visit, whose steps so far replay before it goes on live
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
  if (loadError.value === null) {
    const description = turns.value.length === 0 ? takeUnsentDescription(props.app.applicationId) : null
    if (description) {
      draft.value = description
      send()
    } else {
      followRunningTurn()
    }
  }
  await scrollToBottom()
  input.value?.focus()
}

// the reply shows once the turn's first event replays, so a load with no turn running shows no reply; a turn that ended
// between reading the conversation and following it replays nothing, and the conversation read again holds its reply
function followRunningTurn() {
  followTurn(APP_BUILDER.followRunningTurn(props.app.applicationId), 'Could not follow the running build.')
}

// follows a turn's events into the reply, which starts at the first event unless it shows already
function followTurn(events: Observable<BuildEvent>, failure: string) {
  const stream = {}
  followed = stream
  subscription = events.subscribe({
    next: (event: BuildEvent) => {
      if (followed === stream) {
        if (!streaming.value) {
          startTurn(event.timestamp)
        }
        receive(event)
      }
    },
    error: (err: unknown) => {
      if (followed === stream) {
        if (reply.value) {
          reply.value.error = errorText(err, failure)
          finishTurn()
        } else {
          stopFollowing()
          notice.value = errorText(err, failure)
        }
      }
    },
    complete: () => {
      if (followed === stream) {
        if (reply.value) {
          finishTurn()
        } else {
          stopFollowing()
          if (turns.value[turns.value.length - 1]?.role === 'USER') {
            void reload()
          }
        }
      }
    },
  })
}

function stopFollowing() {
  subscription = null
  followed = null
}

async function reload() {
  try {
    show(await APP_BUILDER.findConversation(props.app.applicationId))
  } catch {
    // the conversation shown stays as it was read
  }
}

// starts the running turn's first reply at the given time
function startTurn(startedAt: number) {
  addReply()
  streaming.value = true
  lastEventAt.value = startedAt
  roundStartedAt.value = startedAt
  stageStep.value = null
  now.value = Date.now()
  clock = setInterval(() => { now.value = Date.now() }, 1000)
}

// adds the assistant's reply the stream fills
function addReply() {
  turns.value.push({ role: 'ASSISTANT', parts: [], error: null })
  // the pushed object is wrapped in a reactive proxy, which the stream's updates must go through to render
  reply.value = turns.value[turns.value.length - 1] as Turn
}

function receive(event: BuildEvent) {
  lastEventAt.value = event.timestamp
  if (event.type === 'USER_MESSAGE') {
    read(event.text ?? '')
  } else if (reply.value) {
    apply(reply.value, event)
  }
}

// a message the turn reads ends the reply so far, and the turn goes on in a reply below it
function read(message: string) {
  const turn = reply.value
  if (turn) {
    settleActiveStep(turn, 'done')
    if (turn.parts.length === 0 && !turn.error) {
      turns.value = turns.value.filter(listed => listed !== turn)
    }
  }
  unqueue(message)
  turns.value.push({ role: 'USER', parts: textParts(message), error: null })
  addReply()
}

function unqueue(message: string) {
  const index = queued.value.indexOf(message)
  if (index >= 0) {
    queued.value.splice(index, 1)
  }
}

function send() {
  const message = draft.value.trim()
  if (!canSend.value || !message) return

  draft.value = ''
  notice.value = null
  void nextTick(autosize)
  if (streaming.value) {
    steer(message)
  } else {
    // a follow still waiting for a running turn is replaced by the turn this message starts
    subscription?.unsubscribe()
    turns.value.push({ role: 'USER', parts: textParts(message), error: null })
    startTurn(Date.now())
    followTurn(APP_BUILDER.chat(props.app.applicationId, message), 'The builder stopped unexpectedly.')
  }
  void scrollToBottom()
}

// a message sent during a turn waits below the reply until the turn reads it; one sent after the turn's last step gets
// a turn of its own, whose events start once the running turn has ended
function steer(message: string) {
  queued.value.push(message)
  let own: object | null = null
  const sent: Subscription = APP_BUILDER.chat(props.app.applicationId, message).subscribe({
    next: (event: BuildEvent) => {
      if (own === null) {
        if (streaming.value) {
          subscription?.unsubscribe()
          finishTurn()
        }
        unqueue(message)
        turns.value.push({ role: 'USER', parts: textParts(message), error: null })
        startTurn(event.timestamp)
        own = {}
        followed = own
        subscription = sent
      }
      if (followed === own) {
        receive(event)
      }
    },
    error: (err: unknown) => {
      if (own === null) {
        unqueue(message)
        notice.value = errorText(err, 'Could not send your message.')
      } else if (followed === own && reply.value) {
        reply.value.error = errorText(err, 'The builder stopped unexpectedly.')
        finishTurn()
      }
    },
    complete: () => {
      if (own !== null && followed === own) {
        finishTurn()
      }
    },
  })
}

function activeStepOf(turn: Turn): Step | null {
  const last = turn.parts[turn.parts.length - 1]
  return last?.type === 'steps' ? last.steps.find(step => step.state === 'active') ?? null : null
}

// adds the step, which sets the stage unless it checks the deployed app; the first to set it after the app went live
// starts a round
function addStageStep(turn: Turn, step: Step) {
  addStep(turn, step)
  if (step.type !== 'CHECKING') {
    if (stageStep.value?.type === 'LIVE') {
      roundStartedAt.value = step.startedAt
    }
    stageStep.value = step
  }
}

function settleActiveStep(turn: Turn, state: Step['state']) {
  const active = activeStepOf(turn)
  if (active) {
    active.state = state
  }
}

// adds the step to the steps after the reply's last text, starting a new run of steps after text
function addStep(turn: Turn, step: Step) {
  const last = turn.parts[turn.parts.length - 1]
  if (last?.type === 'steps') {
    last.steps.push(step)
  } else {
    turn.parts.push({ type: 'steps', steps: [step] })
  }
}

function apply(turn: Turn, event: BuildEvent) {
  switch (event.type) {
    case 'TEXT': {
      settleActiveStep(turn, 'done')
      const last = turn.parts[turn.parts.length - 1]
      if (last?.type === 'text') {
        last.text += event.text ?? ''
      } else if (event.text) {
        turn.parts.push({ type: 'text', text: event.text })
      }
      break
    }
    case 'PROGRESS':
      if (event.text && event.text !== currentStep.value) {
        settleActiveStep(turn, 'done')
        addStageStep(turn, { text: event.text, state: 'active', startedAt: event.timestamp, type: event.stepType })
      }
      break
    case 'APP_READY':
      settleActiveStep(turn, 'done')
      if (event.url) {
        addStageStep(turn, { text: `Live at ${hostOf(event.url)}`, state: 'done', startedAt: event.timestamp, type: 'LIVE' })
        emit('deployed', event.url)
      }
      break
    case 'ERROR':
      settleActiveStep(turn, 'failed')
      turn.error = event.text || 'Something went wrong.'
      break
  }
}

function finishTurn() {
  const turn = reply.value
  streaming.value = false
  stopFollowing()
  reply.value = null
  stopClock()
  if (turn) {
    settleActiveStep(turn, turn.error ? 'failed' : 'done')
    if (turn.parts.length === 0 && !turn.error) {
      turns.value = turns.value.filter(listed => listed !== turn)
    }
    // the messages a failed turn never read go back to the composer, to send again
    if (turn.error && queued.value.length > 0) {
      draft.value = [...queued.value, draft.value].filter(text => text.length > 0).join('\n\n')
      queued.value = []
      void nextTick(autosize)
    }
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

// a reader who scrolls up through history stops following until they scroll back to the bottom
function onScroll() {
  const el = scroller.value
  if (el) {
    following = el.scrollHeight - el.scrollTop - el.clientHeight < FOLLOW_DISTANCE_PX
  }
}

function followNewContent() {
  const el = scroller.value
  if (el && following) {
    el.scrollTop = el.scrollHeight
  }
}

async function scrollToBottom() {
  following = true
  await nextTick()
  followNewContent()
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
.chat {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--panel-solid);
  border-left: 1px solid var(--border);
  box-shadow: -12px 0 32px rgba(0, 0, 0, 0.35);
}

.chat__head {
  display: flex;
  align-items: flex-start;
  gap: 0.35rem;
  padding: 1rem 0.9rem 0.9rem 1.25rem;
  border-bottom: 1px solid var(--border);
}

.chat__heading {
  flex: 1;
  min-width: 0;
}

.chat__title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.chat__subtitle {
  margin: 0.1rem 0 0;
  font-size: 0.85rem;
  color: var(--text-muted);
}

/* shown only when the chat fills the screen, in place of the page behind it */
.chat__status {
  display: none;
  align-items: center;
  gap: 0.45rem;
  margin-top: 0.5rem;
  min-width: 0;
  font-size: 0.82rem;
  font-weight: 500;
}

.chat__status > a,
.chat__status > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__status--working {
  color: var(--accent-link);
}

.chat__status--live {
  color: var(--success-text);
}

.chat__status--idle {
  color: var(--text-muted);
}

.chat__status .icon {
  width: 0.85rem;
  height: 0.85rem;
}

.chat__messages {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1.25rem;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.chat__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  padding: 3rem 0;
  color: var(--text-muted);
}

.chat__empty {
  margin: auto 0;
  padding: 1.5rem 0.5rem;
  text-align: center;
}

.chat__empty h3 {
  margin: 0 0 0.5rem;
  font-size: 1.3rem;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.chat__empty p {
  max-width: 22rem;
  margin: 0 auto 1rem;
}

.idea {
  max-width: 100%;
  padding: 0.5rem 0.9rem;
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text);
  font-size: 0.9rem;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.idea:hover {
  border-color: var(--accent-border);
  background: var(--accent-soft);
}

.msg {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

.msg--user {
  align-items: flex-end;
}

.msg--assistant {
  align-items: flex-start;
}

.msg__bubble {
  max-width: 88%;
  padding: 0.7rem 0.95rem;
  border-radius: 16px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.msg--user .msg__bubble {
  max-width: 85%;
  border: 1px solid rgba(232, 135, 90, 0.35);
  border-bottom-right-radius: 4px;
  background: rgba(232, 135, 90, 0.16);
}

.msg--assistant .msg__bubble {
  border-bottom-left-radius: 4px;
  background: var(--bubble);
}

.msg--queued .msg__bubble {
  opacity: 0.6;
}

.msg__note {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.msg__error {
  max-width: 88%;
  padding: 0.6rem 0.9rem;
  border-radius: 12px;
  background: var(--danger-soft);
  color: var(--danger-text);
  font-size: 0.9rem;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.steps {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
  margin: 0;
  padding: 0.75rem 0.9rem;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.025);
  list-style: none;
  font-size: 0.875rem;
  line-height: 1.4;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  color: var(--text-nav);
  overflow-wrap: anywhere;
}

.step__text {
  flex: 1;
  min-width: 0;
}

.step__elapsed,
.thinking__elapsed {
  flex: none;
  color: var(--text-subtle);
  font-variant-numeric: tabular-nums;
}

.step__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 1.15rem;
  height: 1.15rem;
  border-radius: 50%;
}

.step__icon .icon {
  width: 0.7rem;
  height: 0.7rem;
}

.step__icon .spinner {
  width: 0.8rem;
  height: 0.8rem;
}

.step--done .step__icon {
  background: rgba(95, 207, 158, 0.18);
  color: var(--success-text);
}

.step--active {
  color: var(--accent-link);
}

.step--failed {
  color: var(--danger-text);
}

.step--failed .step__icon {
  background: var(--danger-soft);
}

.thinking {
  display: inline-flex;
  align-items: center;
  gap: 0.55rem;
  align-self: flex-start;
  padding: 0.45rem 0.8rem;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent-link);
  font-size: 0.875rem;
}

.chat__composer {
  padding: 0.75rem 1rem 0.6rem;
  padding-bottom: max(0.6rem, env(safe-area-inset-bottom));
  border-top: 1px solid var(--border);
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
  padding: 0.4rem 0.4rem 0.4rem 0.9rem;
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  background: var(--input-bg);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.composer:focus-within {
  border-color: rgba(232, 135, 90, 0.7);
  box-shadow: 0 0 0 3px rgba(232, 135, 90, 0.18);
}

.composer__input {
  flex: 1;
  min-width: 0;
  min-height: 2.5rem;
  max-height: 200px;
  padding: 0.5rem 0;
  border: none;
  background: transparent;
  color: var(--text);
  outline: none;
  resize: none;
  line-height: 1.5;
}

.composer__input::placeholder {
  color: var(--text-subtle);
}

.composer__send {
  width: 2.5rem;
  min-height: 2.5rem;
  padding: 0;
}

.composer__send .icon {
  width: 1.1rem;
  height: 1.1rem;
}

.composer__hint {
  margin: 0.4rem 0 0;
  font-size: 0.75rem;
  text-align: center;
  color: var(--text-subtle);
}

@media (max-width: 860px) {
  .chat {
    border-left: none;
    box-shadow: none;
  }

  .chat__status {
    display: flex;
  }
}

@media (max-width: 560px) {
  .chat__messages {
    padding: 1rem 0.85rem;
  }

  .msg__bubble,
  .msg--queued .msg__bubble {
  opacity: 0.6;
}

.msg__note {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.msg__error {
    max-width: 92%;
  }

  .composer__hint {
    display: none;
  }
}
</style>
