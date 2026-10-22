import { createApp } from 'vue'
import { Kinotic } from '@kinotic-ai/core'
import { ManagementApiPlugin } from '@kinotic-ai/management-api'
import { PersistencePlugin } from '@kinotic-ai/persistence'
import App from '@/App.vue'
import router from '@/router'
import { onSessionEnded } from '@/api/session'
import '@/style.css'

Kinotic.use(ManagementApiPlugin)
Kinotic.use(PersistencePlugin)

onSessionEnded(() => {
    void router.push({ path: '/login', query: { error: 'session_ended' } })
})

createApp(App).use(router).mount('#app')
