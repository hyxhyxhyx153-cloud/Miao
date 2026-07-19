import { createRouter, createWebHistory } from 'vue-router'
import { getAdminToken } from '../authSession.js'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '仪表盘' } },
      { path: 'users', component: () => import('../views/Users.vue'), meta: { title: '用户管理' } },
      { path: 'api-keys', component: () => import('../views/ApiKeys.vue'), meta: { title: 'API Key' } },
      { path: 'models', component: () => import('../views/Models.vue'), meta: { title: '模型管理' } },
      { path: 'emojis', component: () => import('../views/Emojis.vue'), meta: { title: '表情包' } },
      { path: 'personas', component: () => import('../views/Personas.vue'), meta: { title: '人格管理' } },
      { path: 'wechat', component: () => import('../views/Wechat.vue'), meta: { title: '微信绑定' } },
      { path: 'monitor', component: () => import('../views/Monitor.vue'), meta: { title: '系统监控' } },
      { path: 'app-release', component: () => import('../views/AppRelease.vue'), meta: { title: '应用发布' } },
      { path: 'settings', component: () => import('../views/Settings.vue'), meta: { title: '系统设置' } },
      { path: 'accounts', component: () => import('../views/AdminAccounts.vue'), meta: { title: '管理员账号' } },
      { path: 'audit-logs', component: () => import('../views/AuditLogs.vue'), meta: { title: '审计日志' } },
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const token = getAdminToken()
  if (!to.meta.public && !token) return { path: '/login', replace: true }
})

// A deployed tab can keep an old index while a new build replaces lazy chunks.
// Recover once automatically instead of leaving the content area blank and
// requiring the administrator to refresh by hand.
const CHUNK_RELOAD_KEY = 'admin_chunk_reload_target'
const chunkErrorPattern = /loading chunk|failed to fetch dynamically imported module|importing a module script failed|error loading dynamically imported module/i

router.onError((error, to) => {
  if (!chunkErrorPattern.test(String(error?.message || error))) return
  const target = to?.fullPath || window.location.pathname + window.location.search
  if (sessionStorage.getItem(CHUNK_RELOAD_KEY) === target) return
  sessionStorage.setItem(CHUNK_RELOAD_KEY, target)
  window.location.replace(target)
})

router.afterEach((_to, _from, failure) => {
  if (!failure) sessionStorage.removeItem(CHUNK_RELOAD_KEY)
})

export default router
