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
          <table v-if="users.length + invites.length > 0" class="table">
            <thead>
              <tr><th>User</th><th>Status</th><th>Since</th><th /></tr>
            </thead>
            <tbody>
              <tr v-for="user in users" :key="user.id!">
                <td>{{ user.displayName || user.email }}<span v-if="user.displayName" class="table__sub">{{ user.email }}</span></td>
                <td><span :class="['chip', user.enabled ? 'chip--live' : 'chip--danger']">{{ user.enabled ? 'Active' : 'Disabled' }}</span></td>
                <td class="table__when">{{ formatDate(user.created) }}</td>
                <td>
                  <div class="table__actions">
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === user.id" @click="setEnabled(user.id!, !user.enabled)">
                      {{ user.enabled ? 'Disable' : 'Enable' }}
                    </button>
                    <button type="button" class="btn btn--danger btn--small" :disabled="busy === user.id" @click="remove(user.id!, user.email)">Remove</button>
                  </div>
                </td>
              </tr>
              <tr v-for="invite in invites" :key="invite.id!">
                <td>{{ invite.displayName || invite.email }}<span class="table__sub">{{ invite.invitedByName ? `Invited by ${invite.invitedByName}` : invite.email }}</span></td>
                <td><span class="chip chip--info">Invited</span></td>
                <td class="table__when">{{ formatDate(invite.created) }}</td>
                <td>
                  <div class="table__actions">
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === invite.id" @click="resend(invite)">Resend</button>
                    <button type="button" class="btn btn--danger btn--small" :disabled="busy === invite.id" @click="cancel(invite.id!)">Cancel</button>
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
      <p class="muted invite-note">They set a password from the email, then sign in to {{ app.name }} at its link.</p>
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
import { errorText, formatDate } from '@/api/format'

/** The people who can sign in to an app, and the invitations they have not accepted yet. */
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
  await Kinotic.members.inviteMember(email, displayName, props.app.applicationId)
  await load()
}

function setEnabled(id: string, enabled: boolean) {
  void act(id, () => Kinotic.members.setMemberEnabled(id, enabled), 'Could not change the user.')
}

function remove(id: string, email: string) {
  if (window.confirm(`Remove ${email}? They can no longer sign in to ${props.app.name}.`)) {
    void act(id, () => Kinotic.members.removeMember(id), 'Could not remove the user.')
  }
}

// an application invitation has no resend of its own, so it is cancelled and sent again
function resend(pending: PendingInviteSummary) {
  void act(pending.id!, async () => {
    await Kinotic.members.cancelInvite(pending.id!)
    await Kinotic.members.inviteMember(pending.email, pending.displayName, props.app.applicationId)
  }, 'Could not resend the invitation.')
}

function cancel(id: string) {
  void act(id, () => Kinotic.members.cancelInvite(id), 'Could not cancel the invitation.')
}
</script>

<style scoped>
.invite-note {
  margin: 0;
  font-size: 0.875rem;
}
</style>
