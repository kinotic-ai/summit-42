<template>
  <OrganizationSetup subtitle="One last thing: what should we call the organization your apps live in?"
                  :create="completeSocialSignUp" />
</template>

<script setup lang="ts">
import OrganizationSetup from '@/components/OrganizationSetup.vue'
import { completeSocialSignUp } from '@/api/auth'
</script>
