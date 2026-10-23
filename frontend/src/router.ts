import { createRouter, createWebHistory } from 'vue-router'
import { GITHUB_CALLBACK_PATH } from '@/api/github'
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
        {
            path: '/',
            component: () => import('@/pages/WorkspaceLayout.vue'),
            children: [
                { path: '', component: () => import('@/pages/AppsPage.vue') },
                { path: 'members', component: () => import('@/pages/MembersPage.vue') },
                { path: 'settings', component: () => import('@/pages/WorkspaceSettingsPage.vue') },
            ],
        },
        {
            path: '/apps/:applicationId',
            component: () => import('@/pages/AppLayout.vue'),
            children: [
                { path: '', name: 'build', component: () => import('@/pages/BuildPage.vue') },
                { path: 'overview', component: () => import('@/pages/AppOverviewPage.vue') },
                { path: 'data', component: () => import('@/pages/AppDataPage.vue') },
                { path: 'users', component: () => import('@/pages/AppUsersPage.vue') },
                { path: 'deployments', component: () => import('@/pages/AppDeploymentsPage.vue') },
                { path: 'settings', component: () => import('@/pages/AppSettingsPage.vue') },
            ],
        },
        { path: GITHUB_CALLBACK_PATH, component: () => import('@/pages/GitHubCallbackPage.vue') },
        { path: '/login', component: () => import('@/pages/LoginPage.vue'), meta: { public: true } },
        { path: '/signup', component: () => import('@/pages/SignupPage.vue'), meta: { public: true } },
        { path: '/register', component: () => import('@/pages/RegisterPage.vue'), meta: { public: true } },
        { path: '/invite/accept', component: () => import('@/pages/InviteAcceptPage.vue'), meta: { public: true } },
        { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
})

// a signed-out visit goes to /login with the page it was for as its referer, where the sign-in returns
router.beforeEach(async to => {
    let ret: true | { path: string; query: Record<string, string> } = true
    if (!to.meta.public && !SESSION.connected) {
        const query: Record<string, string> = to.fullPath === '/' ? {} : { referer: to.fullPath }
        try {
            if (!await connect()) {
                ret = { path: '/login', query }
            }
        } catch {
            ret = { path: '/login', query: { ...query, error: 'unreachable' } }
        }
    }
    return ret
})

export default router
