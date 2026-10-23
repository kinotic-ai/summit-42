<template>
  <main class="page">
    <div class="page__inner settings">
      <div class="page__header">
        <div>
          <h1 class="page-title">Organization settings</h1>
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
            <button type="button" class="btn btn--danger btn--small" :disabled="linking" @click="unlink">Unlink</button>
          </template>
          <button v-else-if="!loading" type="button" class="btn btn--small" :disabled="linking" @click="connect">
            <span v-if="linking" class="spinner" /> Connect GitHub
          </button>
        </div>
      </section>

      <section class="panel panel--flush glass">
        <div class="panel__head">
          <div class="providers__intro">
            <h2 class="panel__title">Sign-in providers</h2>
            <p class="muted panel__note">
              Members sign in with GitHub or their email and password. Add your own identity provider, such as Okta,
              Microsoft Entra ID or Google Workspace, and they can sign in with it too.
            </p>
          </div>
          <button type="button" class="btn btn--small" @click="openDrawer(null)"><AppIcon name="plus" :stroke-width="2.25" /> Add provider</button>
        </div>
        <div class="table-wrap">
          <table v-if="providers.length > 0" class="table">
            <thead><tr><th>Name</th><th>Provider</th><th>Status</th><th /></tr></thead>
            <tbody>
              <tr v-for="provider in providers" :key="provider.id!">
                <td>
                  <button type="button" class="link-button" @click="openDrawer(provider)">{{ provider.name }}</button>
                  <span v-if="provider.providerAccount" class="table__sub mono">{{ provider.providerAccount }}</span>
                </td>
                <td class="muted">{{ providerDisplayName(provider.provider) }}</td>
                <td><span :class="['chip', provider.enabled ? 'chip--live' : '']">{{ provider.enabled ? 'Offered' : 'Off' }}</span></td>
                <td>
                  <div class="table__actions">
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === provider.id" @click="openDrawer(provider)">Edit</button>
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === provider.id" @click="setEnabled(provider)">
                      {{ provider.enabled ? 'Turn off' : 'Turn on' }}
                    </button>
                    <button type="button" class="btn btn--danger btn--small" :disabled="busy === provider.id" @click="remove(provider)">Delete</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="table__empty">{{ loading ? 'Loading…' : 'No identity providers of your own yet.' }}</p>
        </div>
      </section>

      <section class="panel glass">
        <h2 class="panel__title">Sign-in page</h2>
        <p class="muted panel__note sign-in__note">Members of {{ SESSION.organizationId }} sign in here with any of the ways above.</p>
        <CopyableUrl :url="signInUrl" />
      </section>
    </div>

    <SignInProviderDrawer v-if="drawerOpen"
                          :configuration="editing"
                          @saved="load"
                          @close="drawerOpen = false" />
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Kinotic, Pageable } from '@kinotic-ai/core'
import { OidcConfigurationType, type GitHubAppInstallation, type OwnedOidcConfiguration } from '@kinotic-ai/management-api'
import AppIcon from '@/components/AppIcon.vue'
import CopyableUrl from '@/components/CopyableUrl.vue'
import SignInProviderDrawer from '@/components/SignInProviderDrawer.vue'
import { providerDisplayName } from '@/api/auth'
import { GITHUB_CALLBACK_PATH } from '@/api/github'
import { errorText } from '@/api/format'
import { SESSION } from '@/api/session'

/** The organization's GitHub link, the identity providers its members sign in with, and its sign-in page. */
const MAX_PROVIDERS = 100

const loading = ref(true)
const error = ref<string | null>(null)
const installation = ref<GitHubAppInstallation | null>(null)
const providers = ref<OwnedOidcConfiguration[]>([])
const linking = ref(false)
const busy = ref<string | null>(null)
const drawerOpen = ref(false)
const editing = ref<OwnedOidcConfiguration | null>(null)

const signInUrl = computed(() => `${window.location.origin}/login/${encodeURIComponent(SESSION.organizationId ?? '')}`)

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
    error.value = errorText(err, 'Could not load the organization settings.')
  } finally {
    loading.value = false
  }
}

async function connect() {
  linking.value = true
  error.value = null
  try {
    window.location.assign(await Kinotic.githubAppInstallations.startInstall(window.location.origin + GITHUB_CALLBACK_PATH))
  } catch (err) {
    error.value = errorText(err, 'Could not start connecting GitHub.')
    linking.value = false
  }
}

async function unlink() {
  if (!window.confirm('Unlink GitHub? Summit 42 can no longer change your apps until you connect it again. Your repositories are kept.')) return
  linking.value = true
  error.value = null
  try {
    await Kinotic.githubAppInstallations.unlink()
    installation.value = null
  } catch (err) {
    error.value = errorText(err, 'Could not unlink GitHub.')
  } finally {
    linking.value = false
  }
}

function openDrawer(provider: OwnedOidcConfiguration | null) {
  editing.value = provider
  drawerOpen.value = true
}

async function act(provider: OwnedOidcConfiguration, action: () => Promise<unknown>, fallback: string) {
  busy.value = provider.id!
  error.value = null
  try {
    await action()
    await load()
  } catch (err) {
    error.value = errorText(err, fallback)
  } finally {
    busy.value = null
  }
}

// saved without a secret, so the one held stays as it is
function setEnabled(provider: OwnedOidcConfiguration) {
  void act(provider, () => Kinotic.ownedOidcConfigurations.save({ ...provider, enabled: !provider.enabled }, null),
           'Could not change the provider.')
}

function remove(provider: OwnedOidcConfiguration) {
  if (window.confirm(`Delete ${provider.name}? Sign-ins through it stop at once, and its client secret is deleted.`)) {
    void act(provider, () => Kinotic.ownedOidcConfigurations.deleteById(provider.id!), 'Could not delete the provider.')
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

.providers__intro {
  flex: 1 1 24rem;
  max-width: 44rem;
}

.sign-in__note {
  margin-bottom: 0.9rem;
}
</style>
