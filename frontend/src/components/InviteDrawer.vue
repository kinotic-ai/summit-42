<template>
  <SideDrawer :title="title" :subtitle="subtitle" @close="emit('close')">
    <form id="invite-form" @submit.prevent="submit">
      <div v-if="error" class="alert alert--error" role="alert">{{ error }}</div>
      <div class="field">
        <label for="invite-email">Email</label>
        <input id="invite-email" v-model="email" class="input" type="email" autocomplete="off" placeholder="name@example.com" required />
      </div>
      <div class="field">
        <label for="invite-name">Name <span class="muted">(optional)</span></label>
        <input id="invite-name" v-model="displayName" class="input" autocomplete="off" />
      </div>
      <slot />
    </form>
    <template #footer>
      <button type="button" class="btn btn--secondary" :disabled="sending" @click="emit('close')">Cancel</button>
      <button type="submit" form="invite-form" class="btn" :disabled="sending || email.trim().length === 0">
        <span v-if="sending" class="spinner" />
        Send invitation
      </button>
    </template>
  </SideDrawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import SideDrawer from '@/components/SideDrawer.vue'
import { errorText } from '@/api/format'

/** Asks for the email and name of someone to invite, and calls {@code invite} with them. */
const props = defineProps<{
  title: string
  subtitle: string
  invite: (email: string, displayName: string | null) => Promise<void>
}>()

const emit = defineEmits<{
  /** Cancelled, or the invitation was sent. */
  close: []
}>()

const email = ref('')
const displayName = ref('')
const sending = ref(false)
const error = ref<string | null>(null)

async function submit() {
  const address = email.value.trim()
  if (!address || sending.value) return
  sending.value = true
  error.value = null
  try {
    await props.invite(address, displayName.value.trim() || null)
    emit('close')
  } catch (err) {
    error.value = errorText(err, 'Could not send the invitation.')
  } finally {
    sending.value = false
  }
}
</script>
