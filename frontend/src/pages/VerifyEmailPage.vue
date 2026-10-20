<template>
  <WorkspaceSetup subtitle="Your email is verified. Name your workspace and set a password."
                  :initial-name="initialName"
                  :create="create">
    <div class="field">
      <label for="password">Password</label>
      <input id="password" v-model="password" class="input" type="password" autocomplete="new-password" required />
    </div>
    <div class="field">
      <label for="confirm">Confirm password</label>
      <input id="confirm" v-model="confirm" class="input" type="password" autocomplete="new-password" required />
    </div>
  </WorkspaceSetup>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import WorkspaceSetup from '@/components/WorkspaceSetup.vue'
import { completeSignUp, rememberedSignupName, rememberSignupName } from '@/api/auth'

const storedName = rememberedSignupName()
const initialName = storedName ? `${storedName}'s apps` : ''
const password = ref('')
const confirm = ref('')

async function create(token: string, workspaceName: string): Promise<void> {
  if (!password.value) {
    throw new Error('Choose a password.')
  }
  if (password.value !== confirm.value) {
    throw new Error('The passwords do not match.')
  }
  await completeSignUp(token, workspaceName, password.value)
  rememberSignupName(null)
}
</script>
