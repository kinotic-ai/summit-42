<template>
  <main class="page">
    <div class="page__inner settings">
      <div class="page__header">
        <div>
          <h1 class="page-title">Workspace settings</h1>
          <p class="page-subtitle">GitHub, and how members sign in.</p>
        </div>
      </div>

      <div v-if="error" class="alert alert--error" role="alert">
        {{ error }}
        <button type="button" class="link-button" @click="load">Try again</button>
      </div>

      <section class="panel glass github">
        <span class="github__mark" aria-hidden="true">
          <svg viewBox="0 0 16 16"><path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" /></svg>
        </span>
        <div class="github__text">
          <h2 class="panel__title">GitHub</h2>
          <p v-if="loading" class="muted">Loading…</p>
          <p v-else-if="installation" class="muted">Linked as <strong class="github__login">{{ installation.accountLogin }}</strong>. Each app's code lives in a repository there.</p>
          <p v-else class="muted">Not linked. Summit 42 needs GitHub to keep each app's code.</p>
        </div>
        <div class="github__actions">
          <template v-if="installation">
            <a :href="manageUrl" target="_blank" rel="noopener" class="btn btn--secondary btn--small">Manage on GitHub <AppIcon name="external" /></a>
            <button type="button" class="btn btn--danger btn--small" :disabled="busy" @click="unlink">Unlink</button>
          </template>
          <button v-else-if="!loading" type="button" class="btn btn--small" :disabled="busy" @click="connect">
            <span v-if="busy" class="spinner" /> Connect GitHub
          </button>
        </div>
      </section>

      <section class="panel panel--flush glass">
        <div class="panel__head">
          <div>
            <h2 class="panel__title">Sign-in methods</h2>
            <p class="muted panel__note">Members can always sign in with their email and password. These add sign-in with your own identity providers.</p>
          </div>
        </div>
        <div class="table-wrap">
          <table v-if="providers.length > 0" class="table">
            <thead><tr><th>Method</th><th>Provider</th><th>Status</th></tr></thead>
            <tbody>
              <tr v-for="provider in providers" :key="provider.id!">
                <td>{{ provider.name }}</td>
                <td class="muted">{{ provider.provider }}</td>
                <td><span :class="['chip', provider.enabled ? 'chip--live' : '']">{{ provider.enabled ? 'On' : 'Off' }}</span></td>
              </tr>
            </tbody>
          </table>
          <p v-else class="table__empty">{{ loading ? 'Loading…' : 'No identity providers of your own.' }}</p>
        </div>
      </section>

      <section class="panel glass workspace-id">
        <div>
          <h2 class="panel__title">Workspace</h2>
          <p class="muted panel__note">Members sign in with this workspace ID.</p>
        </div>
        <button type="button" class="btn btn--secondary btn--small" @click="copyId">
          <span class="mono">{{ SESSION.organizationId }}</span>
          <AppIcon :name="copied ? 'check' : 'copy'" />
        </button>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Kinotic, Pageable } from '@kinotic-ai/core'
import { OidcConfigurationType, type GitHubAppInstallation, type OwnedOidcConfiguration } from '@kinotic-ai/management-api'
import AppIcon from '@/components/AppIcon.vue'
import { GITHUB_CALLBACK_PATH } from '@/api/github'
import { copyText, errorText } from '@/api/format'
import { SESSION } from '@/api/session'

/** The workspace's GitHub link, the ways its members sign in, and its id. */
const MAX_PROVIDERS = 100

const loading = ref(true)
const error = ref<string | null>(null)
const installation = ref<GitHubAppInstallation | null>(null)
const providers = ref<OwnedOidcConfiguration[]>([])
const busy = ref(false)
const copied = ref(false)

// GitHub manages an organization's installations under the organization, and a user's under their own settings
const manageUrl = computed(() => {
  const found = installation.value
  let ret = 'https://github.com/settings/installations'
  if (found && found.accountType === 'Organization') {
    ret = `https://github.com/organizations/${found.accountLogin}/settings/installations/${found.githubInstallationId}`
  } else if (found) {
    ret = `https://github.com/settings/installations/${found.githubInstallationId}`
  }
  return ret
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [found, configurations] = await Promise.all([
      Kinotic.githubAppInstallations.findForCurrentOrg(),
      Kinotic.ownedOidcConfigurations.findAll(Pageable.create(0, MAX_PROVIDERS)),
    ])
    installation.value = found
    providers.value = (configurations.content ?? []).filter(configuration => configuration.type === OidcConfigurationType.ORGANIZATION)
  } catch (err) {
    error.value = errorText(err, 'Could not load the workspace settings.')
  } finally {
    loading.value = false
  }
}

async function connect() {
  busy.value = true
  error.value = null
  try {
    window.location.assign(await Kinotic.githubAppInstallations.startInstall(window.location.origin + GITHUB_CALLBACK_PATH))
  } catch (err) {
    error.value = errorText(err, 'Could not start connecting GitHub.')
    busy.value = false
  }
}

async function unlink() {
  if (!window.confirm('Unlink GitHub? Summit 42 can no longer change your apps until you connect it again. Your repositories are kept.')) return
  busy.value = true
  error.value = null
  try {
    await Kinotic.githubAppInstallations.unlink()
    installation.value = null
  } catch (err) {
    error.value = errorText(err, 'Could not unlink GitHub.')
  } finally {
    busy.value = false
  }
}

async function copyId() {
  if (SESSION.organizationId && await copyText(SESSION.organizationId)) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}
</script>

<style scoped>
.settings {
  max-width: 60rem;
}

.github {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem 1.25rem;
}

.github__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 2.75rem;
  height: 2.75rem;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
}

.github__mark svg {
  width: 1.5rem;
  height: 1.5rem;
}

.github__text {
  flex: 1 1 16rem;
  min-width: 0;
}

.github__text p {
  margin: 0.15rem 0 0;
  font-size: 0.92rem;
}

.github__login {
  color: var(--text);
}

.github__actions {
  display: flex;
  gap: 0.5rem;
}

.panel__note {
  margin: 0.15rem 0 0;
  font-size: 0.875rem;
}

.workspace-id {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 1rem;
}
</style>
