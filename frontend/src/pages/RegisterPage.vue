<template>
  <WorkspaceSetup subtitle="One last thing: what should we call the workspace your apps live in?"
                  :create="completeSocialSignUp" />
</template>

<script setup lang="ts">
import WorkspaceSetup from '@/components/WorkspaceSetup.vue'
import { completeSocialSignUp } from '@/api/auth'
</script>
