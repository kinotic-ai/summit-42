<template>
  <main class="page">
    <div class="page__inner">
      <div class="page__header">
        <div>
          <h1 class="page-title">Settings</h1>
          <p class="page-subtitle">How {{ app.name }} is named, who signs in to it, and where its code lives.</p>
        </div>
      </div>

      <div class="settings">
        <section class="panel glass">
          <h2 class="panel__title settings__title">General</h2>
          <form @submit.prevent="save">
            <div v-if="saveError" class="alert alert--error" role="alert">{{ saveError }}</div>
            <div v-if="saved" class="alert alert--success" role="status">Saved.</div>
            <div class="field">
              <label for="settings-name">Name</label>
              <input id="settings-name" v-model="name" class="input" autocomplete="off" required />
              <span class="hint">The app's id and link stay the same when you rename it.</span>
            </div>
            <div class="field">
              <label for="settings-description">What it's for</label>
              <textarea id="settings-description" v-model="description" class="input" rows="3" />
            </div>
            <div class="settings__actions">
              <button type="button" class="btn btn--secondary" :disabled="saving || !dirty" @click="reset">Discard</button>
              <button type="submit" class="btn" :disabled="saving || !dirty || name.trim().length === 0">
                <span v-if="saving" class="spinner" /> Save changes
              </button>
            </div>
          </form>
        </section>

        <div class="settings__side">
          <section class="panel glass">
            <h2 class="panel__title settings__title">Details</h2>
            <dl class="details">
              <dt>App ID</dt>
              <dd>
                <span class="mono">{{ app.applicationId }}</span>
                <button type="button" class="icon-btn details__copy" aria-label="Copy the app ID" @click="copyId">
                  <AppIcon :name="copied ? 'check' : 'copy'" />
                </button>
              </dd>
              <dt>Code</dt>
              <dd>
                <a v-if="project?.repoFullName" :href="repoUrl(project.repoFullName)" target="_blank" rel="noopener">{{ project.repoFullName }}</a>
                <span v-else class="muted">{{ loadingProject ? '…' : '—' }}</span>
              </dd>
              <dt>Link</dt>
              <dd>
                <a v-if="app.url" :href="app.url" target="_blank" rel="noopener">{{ hostOf(app.url) }}</a>
                <span v-else class="muted">Not deployed yet</span>
              </dd>
            </dl>
          </section>

          <section class="panel glass danger">
            <h2 class="panel__title danger__title">Delete app</h2>
            <p class="muted danger__text">Removes {{ app.name }}, what it deployed, and its users' access. The GitHub repository is kept.</p>
            <div v-if="deleteError" class="alert alert--error" role="alert">{{ deleteError }}</div>
            <button type="button" class="btn btn--danger" :disabled="deleting" @click="remove">
              <span v-if="deleting" class="spinner" /> Delete {{ app.name }}
            </button>
          </section>
        </div>
      </div>

      <SignInProvidersPanel :application-id="app.applicationId" class="sign-in" />
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Kinotic } from '@kinotic-ai/core'
import type { Project } from '@kinotic-ai/management-api'
import AppIcon from '@/components/AppIcon.vue'
import SignInProvidersPanel from '@/components/SignInProvidersPanel.vue'
import { markRenamed, type BuilderApp } from '@/api/appBuilder'
import { deleteApp, findProjects, repoUrl } from '@/api/appProjects'
import { copyText, errorText, hostOf } from '@/api/format'

/** An app's name and description, who signs in to it and how, where its code lives, and deleting it. */
const props = defineProps<{
  app: BuilderApp
}>()

const router = useRouter()

const name = ref(props.app.name)
const description = ref(props.app.description ?? '')
const saving = ref(false)
const saved = ref(false)
const saveError = ref<string | null>(null)
const project = ref<Project | null>(null)
const loadingProject = ref(true)
const copied = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

const dirty = computed(() => name.value.trim() !== props.app.name || description.value.trim() !== (props.app.description ?? ''))

onMounted(async () => {
  try {
    project.value = (await findProjects(props.app.applicationId))[0] ?? null
  } catch {
    // the details panel shows a dash for the repository it could not read
  } finally {
    loadingProject.value = false
  }
})

function reset() {
  name.value = props.app.name
  description.value = props.app.description ?? ''
}

async function save() {
  saving.value = true
  saved.value = false
  saveError.value = null
  try {
    const application = await Kinotic.applications.findById(props.app.applicationId)
    if (!application) {
      throw new Error(`${props.app.name} no longer exists.`)
    }
    const newName = name.value.trim()
    const newDescription = description.value.trim()
    await Kinotic.applications.save({ ...application, name: newName, description: newDescription })
    markRenamed(props.app.applicationId, newName, newDescription || null)
    saved.value = true
  } catch (err) {
    saveError.value = errorText(err, 'Could not save the changes.')
  } finally {
    saving.value = false
  }
}

async function copyId() {
  if (await copyText(props.app.applicationId)) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

async function remove() {
  if (!window.confirm(`Delete ${props.app.name}? Its deployment and its users' access are removed. The GitHub repository is kept.`)) return
  deleting.value = true
  deleteError.value = null
  try {
    await deleteApp(props.app.applicationId)
    await router.replace('/')
  } catch (err) {
    deleteError.value = errorText(err, `Could not delete ${props.app.name}.`)
    deleting.value = false
  }
}
</script>

<style scoped>
.settings {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 1.25rem;
  align-items: start;
}

.settings__title {
  margin-bottom: 1rem;
}

.sign-in {
  margin-top: 1.25rem;
}

.settings__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}

.details {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 0.6rem 1rem;
  margin: 0;
  font-size: 0.92rem;
}

.details dt {
  color: var(--text-muted);
}

.details dd {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.details__copy {
  width: 1.75rem;
  height: 1.75rem;
}

.details__copy .icon {
  width: 0.95rem;
  height: 0.95rem;
}

.danger {
  border-color: rgba(242, 139, 130, 0.3);
}

.danger__title {
  color: var(--danger-text);
}

.danger__text {
  margin: 0.4rem 0 1rem;
  font-size: 0.9rem;
}

@media (max-width: 860px) {
  .settings {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
