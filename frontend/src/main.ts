import { createApp } from 'vue'
import App from '@/App.vue'
import router from '@/router'
import { onSessionEnded } from '@/api/session'
import '@/style.css'

onSessionEnded(() => {
    void router.push({ path: '/login', query: { error: 'session_ended' } })
})

createApp(App).use(router).mount('#app')
