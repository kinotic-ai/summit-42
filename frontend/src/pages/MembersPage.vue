<template>
  <main class="page">
    <div class="page__inner">
      <div class="page__header">
        <div>
          <h1 class="page-title">Members</h1>
          <p class="page-subtitle">People who can build and change apps in this organization.</p>
        </div>
        <button type="button" class="btn" @click="inviting = true"><AppIcon name="plus" :stroke-width="2.25" /> Invite member</button>
      </div>

      <div v-if="error" class="alert alert--error" role="alert">
        {{ error }}
        <button type="button" class="link-button" @click="load">Try again</button>
      </div>

      <section class="panel panel--flush glass">
        <div class="table-wrap">
          <table v-if="members.length > 0" class="table">
            <thead>
              <tr><th>Member</th><th>Status</th><th>Joined</th><th /></tr>
            </thead>
            <tbody>
              <tr v-for="member in members" :key="member.email">
                <td>
                  <div class="member">
                    <span :class="['member__avatar', { 'member__avatar--pending': member.pending }]">{{ member.pending ? '@' : initialsOf(member.displayName || member.email) }}</span>
                    <span>
                      {{ member.displayName || member.email }}<span v-if="isSelf(member)" class="muted"> (you)</span>
                      <span class="table__sub">{{ member.pending ? pendingNote(member) : member.email }}</span>
                    </span>
                  </div>
                </td>
                <td><span :class="['chip', statusOf(member).class]">{{ statusOf(member).text }}</span></td>
                <td class="table__when">{{ member.pending ? '—' : formatDate(member.created) }}</td>
                <td>
                  <div v-if="member.pending" class="table__actions">
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === member.email" @click="resend(member)">Resend</button>
                    <button type="button" class="btn btn--danger btn--small" :disabled="busy === member.email" @click="remove(member)">Remove</button>
                  </div>
                  <div v-else-if="!isSelf(member) && member.id" class="table__actions">
                    <button type="button" class="btn btn--secondary btn--small" :disabled="busy === member.email" @click="setEnabled(member)">
                      {{ member.enabled ? 'Disable' : 'Enable' }}
                    </button>
                    <button type="button" class="btn btn--danger btn--small" :disabled="busy === member.email" @click="remove(member)">Remove</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="table__empty">{{ loading ? 'Loading…' : 'No members yet.' }}</p>
        </div>
      </section>
    </div>

    <InviteDrawer v-if="inviting"
                  title="Invite member"
                  subtitle="They get an email with a link to join this organization."
                  :invite="invite"
                  @close="inviting = false">
      <p class="muted invite-note">Members see every app in the organization and can change any of them in the chat.</p>
    </InviteDrawer>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Kinotic, Pageable } from '@kinotic-ai/core'
import type { OrganizationParticipantIdentity, PendingInviteSummary } from '@kinotic-ai/management-api'
import AppIcon from '@/components/AppIcon.vue'
import InviteDrawer from '@/components/InviteDrawer.vue'
import { INVITATIONS } from '@/api/invitations'
import { errorText, formatDate, initialsOf } from '@/api/format'
import { SESSION } from '@/api/session'

/** The organization's members: its users, each pending until they first sign in, and the invitations they were sent. */
const MAX_ROWS = 200

const loading = ref(true)
const error = ref<string | null>(null)
const members = ref<OrganizationParticipantIdentity[]>([])
const invites = ref<PendingInviteSummary[]>([])
const inviting = ref(false)
const busy = ref<string | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [foundMembers, foundInvites] = await Promise.all([
      Kinotic.members.findMembers(Pageable.create(0, MAX_ROWS)),
      Kinotic.members.findPendingInvites(null, Pageable.create(0, MAX_ROWS)),
    ])
    members.value = foundMembers.content ?? []
    invites.value = foundInvites.content ?? []
  } catch (err) {
    error.value = errorText(err, 'Could not load the members.')
  } finally {
    loading.value = false
  }
}

function isSelf(member: OrganizationParticipantIdentity): boolean {
  return SESSION.email !== null && member.email.toLowerCase() === SESSION.email.toLowerCase()
}

function statusOf(member: OrganizationParticipantIdentity): { text: string; class: string } {
  let ret: { text: string; class: string }
  if (member.pending) {
    ret = { text: 'Invited', class: 'chip--info' }
  } else if (member.enabled) {
    ret = { text: 'Active', class: 'chip--live' }
  } else {
    ret = { text: 'Disabled', class: 'chip--danger' }
  }
  return ret
}

function inviteFor(member: OrganizationParticipantIdentity): PendingInviteSummary | undefined {
  return invites.value.find(invite => invite.email.toLowerCase() === member.email.toLowerCase())
}

function pendingNote(member: OrganizationParticipantIdentity): string {
  const sent = inviteFor(member)?.created
  return sent ? `Invitation sent ${formatDate(sent)}` : member.email
}

async function act(member: OrganizationParticipantIdentity, action: () => Promise<unknown>, fallback: string) {
  busy.value = member.email
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
  await INVITATIONS.addOrganizationUser(email, displayName)
  await load()
}

function resend(member: OrganizationParticipantIdentity) {
  void act(member, () => INVITATIONS.sendInvitation(member.email, null), 'Could not resend the invitation.')
}

function setEnabled(member: OrganizationParticipantIdentity) {
  void act(member, () => Kinotic.members.setMemberEnabled(member.id!, !member.enabled), 'Could not change the member.')
}

function remove(member: OrganizationParticipantIdentity) {
  const question = member.pending
    ? `Remove ${member.displayName || member.email}? Nobody has signed in as them yet, so their invitation stops working.`
    : `Remove ${member.displayName || member.email} from the organization?`
  if (window.confirm(question)) {
    void act(member, () => Kinotic.members.removeMember(member.id!), 'Could not remove the member.')
  }
}
</script>

<style scoped>
.member {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.member__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 2.1rem;
  height: 2.1rem;
  border-radius: 50%;
  background: var(--raised);
  font-size: 0.75rem;
  font-weight: 600;
}

.member__avatar--pending {
  border: 1px dashed rgba(255, 255, 255, 0.3);
  background: transparent;
  color: var(--text-muted);
}

.invite-note {
  margin: 0;
  font-size: 0.875rem;
}
</style>
