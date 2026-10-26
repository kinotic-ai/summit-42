<template>
  <main class="page">
    <div class="page__inner">
      <div class="page__header">
        <div>
          <h1 class="page-title">Users</h1>
          <p class="page-subtitle">People who can sign in to {{ app.name }}.</p>
        </div>
        <button type="button" class="btn" @click="inviting = true"><AppIcon name="plus" :stroke-width="2.25" /> Invite user</button>
      </div>

      <div v-if="error" class="alert alert--error" role="alert">
        {{ error }}
        <button type="button" class="link-button" @click="load">Try again</button>
      </div>

      <section class="panel panel--flush glass">
        <div class="table-wrap">
          <table v-if="users.length > 0" class="table">
            <thead>
              <tr><th>User</th><th>Status</th><th>Since</th><th /></tr>
            </thead>
            <tbody>
              <tr v-for="user in users" :key="user.id!">
                <td>
                  {{ user.displayName || user.email }}
                  <span class="table__sub">{{ user.pending ? pendingNote(user) : user.email }}</span>
                </td>
                <td><span :class="['chip', statusOf(user).class]">{{ statusOf(user).text }}</span></td>
                <td class="table__when">{{ user.pending ? '—' : formatDate(user.created) }}</td>
                <td>
                  <div v-if="user.pending" class="table__actions">
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === user.id" @click="resend(user)">Resend</button>
                    <button type="button" class="btn btn--danger btn--small" :disabled="busy === user.id" @click="remove(user)">Remove</button>
                  </div>
                  <div v-else class="table__actions">
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === user.id" @click="setEnabled(user.id!, !user.enabled)">
                      {{ user.enabled ? 'Disable' : 'Enable' }}
                    </button>
                    <button type="button" class="btn btn--danger btn--small" :disabled="busy === user.id" @click="remove(user)">Remove</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="table__empty">{{ loading ? 'Loading…' : `No one can sign in to ${app.name} yet. Invite someone to get started.` }}</p>
        </div>
      </section>
    </div>

    <InviteDrawer v-if="inviting"
                  title="Invite user"
                  :subtitle="`They get an email with a link to join ${app.name}.`"
                  :invite="invite"
                  @close="inviting = false">
      <p class="muted invite-note">
        They set a password from the email, or sign in to {{ app.name }} through one of its sign-in providers, and use it
        at its link from then on.
      </p>
    </InviteDrawer>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Kinotic, Pageable } from '@kinotic-ai/core'
import type { ApplicationParticipantIdentity, PendingInviteSummary } from '@kinotic-ai/management-api'
import AppIcon from '@/components/AppIcon.vue'
import InviteDrawer from '@/components/InviteDrawer.vue'
import type { BuilderApp } from '@/api/appBuilder'
import { INVITATIONS } from '@/api/invitations'
import { errorText, formatDate } from '@/api/format'

/** The people who can sign in to an app, each pending until they first sign in, and the invitations they were sent. */
const props = defineProps<{
  app: BuilderApp
}>()

const MAX_ROWS = 200

const loading = ref(true)
const error = ref<string | null>(null)
const users = ref<ApplicationParticipantIdentity[]>([])
const invites = ref<PendingInviteSummary[]>([])
const inviting = ref(false)
const busy = ref<string | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [foundUsers, foundInvites] = await Promise.all([
      Kinotic.members.findApplicationUsers(props.app.applicationId, Pageable.create(0, MAX_ROWS)),
      Kinotic.members.findPendingInvites(props.app.applicationId, Pageable.create(0, MAX_ROWS)),
    ])
    users.value = foundUsers.content ?? []
    invites.value = foundInvites.content ?? []
  } catch (err) {
    error.value = errorText(err, 'Could not load the users.')
  } finally {
    loading.value = false
  }
}

function statusOf(user: ApplicationParticipantIdentity): { text: string; class: string } {
  let ret: { text: string; class: string }
  if (user.pending) {
    ret = { text: 'Invited', class: 'chip--info' }
  } else if (user.enabled) {
    ret = { text: 'Active', class: 'chip--live' }
  } else {
    ret = { text: 'Disabled', class: 'chip--danger' }
  }
  return ret
}

function pendingNote(user: ApplicationParticipantIdentity): string {
  const sent = invites.value.find(invite => invite.email.toLowerCase() === user.email.toLowerCase())?.created
  return sent ? `Invitation sent ${formatDate(sent)}` : user.email
}

async function act(id: string, action: () => Promise<unknown>, fallback: string) {
  busy.value = id
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

async function invite(email: string, displayName: string | null) {
  await INVITATIONS.addApplicationUser(email, displayName, props.app.applicationId)
  await load()
}

function setEnabled(id: string, enabled: boolean) {
  void act(id, () => Kinotic.members.setMemberEnabled(id, enabled), 'Could not change the user.')
}

function resend(user: ApplicationParticipantIdentity) {
  void act(user.id!, () => INVITATIONS.sendInvitation(user.email, props.app.applicationId), 'Could not resend the invitation.')
}

// removing a pending user cancels the invitation they never accepted
function remove(user: ApplicationParticipantIdentity) {
  const question = user.pending
    ? `Remove ${user.displayName || user.email}? Nobody has signed in as them yet, so their invitation stops working.`
    : `Remove ${user.email}? They can no longer sign in to ${props.app.name}.`
  if (window.confirm(question)) {
    void act(user.id!, () => Kinotic.members.removeMember(user.id!), 'Could not remove the user.')
  }
}
</script>

<style scoped>
.invite-note {
  margin: 0;
  font-size: 0.875rem;
}
</style>
