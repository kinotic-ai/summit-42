import { createRouter, createWebHistory } from 'vue-router'
import { connect, SESSION } from '@/api/session'

declare module 'vue-router' {
    interface RouteMeta {
        /** True for the sign-in and sign-up pages, which need no session. */
        public?: boolean
    }
}

const router = createRouter({
    history: createWebHistory(),
    routes: [
        { path: '/', component: () => import('@/pages/WorkspacePage.vue') },
        { path: '/login', component: () => import('@/pages/LoginPage.vue'), meta: { public: true } },
        { path: '/signup', component: () => import('@/pages/SignupPage.vue'), meta: { public: true } },
        { path: '/signup/verify', component: () => import('@/pages/VerifyEmailPage.vue'), meta: { public: true } },
        { path: '/register', component: () => import('@/pages/RegisterPage.vue'), meta: { public: true } },
        { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
})

router.beforeEach(async to => {
    let ret: true | { path: string; query?: Record<string, string> } = true
    if (!to.meta.public && !SESSION.connected) {
        try {
            if (!await connect()) {
                ret = { path: '/login' }
            }
        } catch {
            ret = { path: '/login', query: { error: 'unreachable' } }
        }
    }
    return ret
})

export default router
