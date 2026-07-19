<template>
  <div class="admin-shell" :class="{ 'nav-collapsed': effectiveCollapsed, 'mobile-nav-open': mobileOpen }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <div class="ambient ambient-one" aria-hidden="true" />
    <div class="ambient ambient-two" aria-hidden="true" />

    <aside class="glass-sidebar" aria-label="管理后台主导航">
      <button class="brand" type="button" :aria-label="effectiveCollapsed ? '折叠导航' : '收起导航'" @click="toggleSidebar">
        <span class="brand-mark" aria-hidden="true">
          <span class="brand-ear brand-ear-left" />
          <span class="brand-ear brand-ear-right" />
          <span class="brand-face">m</span>
        </span>
        <span class="brand-copy">
          <strong>Miao</strong>
          <small>Console</small>
        </span>
        <el-icon class="collapse-indicator"><ArrowLeftBold /></el-icon>
      </button>

      <div class="nav-scroll">
        <div class="nav-label">工作台</div>
        <el-menu :default-active="activePath" :collapse="effectiveCollapsed" @select="navigate">
          <el-menu-item v-for="item in primaryNav" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.label }}</template>
          </el-menu-item>
        </el-menu>

        <div class="nav-label">系统</div>
        <el-menu :default-active="activePath" :collapse="effectiveCollapsed" @select="navigate">
          <el-menu-item v-for="item in systemNav" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.label }}</template>
          </el-menu-item>
        </el-menu>
      </div>

      <div class="sidebar-footer">
        <span class="status-dot session-dot" aria-hidden="true" />
        <span class="sidebar-footer-copy">
          <strong>管理员已登录</strong>
          <small>闲置 30 分钟后退出</small>
        </span>
      </div>
    </aside>

    <button v-if="mobileOpen" class="mobile-scrim" type="button" aria-label="关闭导航" @click="mobileOpen=false" />

    <section class="workspace">
      <header class="glass-topbar">
        <button class="mobile-menu-button" type="button" aria-label="打开导航" @click="mobileOpen=true">
          <el-icon><Menu /></el-icon>
        </button>
        <div class="page-heading">
          <span class="page-kicker">Miao 管理中心</span>
          <h1>{{ pageTitle }}</h1>
        </div>

        <div class="topbar-actions">
          <div class="session-state" title="当前已登录管理员账号">
            <span class="status-dot session-dot" aria-hidden="true" />
            <span>已登录</span>
          </div>
          <el-dropdown trigger="click" @command="onCommand">
            <button class="account-chip" type="button" aria-label="打开管理员菜单">
              <span class="account-avatar">{{ adminInitial }}</span>
              <span class="account-copy">
                <strong>{{ adminUser?.username || 'admin' }}</strong>
                <small>管理员</small>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main id="main-content" class="content-area" tabindex="-1" :aria-busy="navigating">
        <div class="content-frame">
          <router-view v-slot="{ Component }">
            <component :is="Component" :key="route.fullPath" />
          </router-view>
        </div>
      </main>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearAdminSession, getAdminUser } from '../authSession.js'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const mobileOpen = ref(false)
const compactViewport = ref(false)
const navigating = ref(false)
const adminUser = getAdminUser()
let navigationSequence = 0

const primaryNav = [
  { path: '/dashboard', label: '仪表盘', icon: 'DataLine' },
  { path: '/users', label: '用户管理', icon: 'User' },
  { path: '/api-keys', label: 'API Key', icon: 'Key' },
  { path: '/models', label: '模型管理', icon: 'Cpu' },
  { path: '/emojis', label: '表情包', icon: 'Picture' },
  { path: '/personas', label: '人格管理', icon: 'ChatDotRound' },
  { path: '/wechat', label: '微信绑定', icon: 'ChatLineRound' },
]

const systemNav = [
  { path: '/monitor', label: '系统监控', icon: 'Monitor' },
  { path: '/app-release', label: '应用发布', icon: 'Promotion' },
  { path: '/settings', label: '系统设置', icon: 'Tools' },
  { path: '/accounts', label: '管理员账号', icon: 'Avatar' },
  { path: '/audit-logs', label: '审计日志', icon: 'DocumentChecked' },
]

const titleMap = Object.fromEntries([...primaryNav, ...systemNav].map(item => [item.path, item.label]))
const pageTitle = computed(() => route.meta.title || titleMap[route.path] || '后台管理')
const activePath = computed(() => route.matched.at(-1)?.path || route.path)
const adminInitial = computed(() => (adminUser?.username || 'A').slice(0, 1).toUpperCase())
const effectiveCollapsed = computed(() => !mobileOpen.value && (collapsed.value || compactViewport.value))

function toggleSidebar() {
  if (window.matchMedia('(max-width: 860px)').matches) mobileOpen.value = !mobileOpen.value
  else collapsed.value = !collapsed.value
}

async function navigate(path) {
  mobileOpen.value = false
  if (path === route.path) return
  const sequence = ++navigationSequence
  navigating.value = true
  try {
    await router.push(path)
    await nextTick()
    document.getElementById('main-content')?.focus({ preventScroll: true })
  } catch (error) {
    // Known stale-chunk failures are recovered by router.onError. Keeping the
    // current view mounted makes every other navigation failure non-destructive.
    console.error('管理后台页面导航失败', error)
  } finally {
    if (sequence === navigationSequence) navigating.value = false
  }
}

function onCommand(cmd) {
  if (cmd === 'logout') {
    clearAdminSession()
    router.push('/login')
  }
}

const INACTIVITY_MS = 30 * 60 * 1000
let inactivityTimer
let compactQuery
function syncCompactViewport(event) {
  compactViewport.value = event.matches
}
function logoutForInactivity() {
  clearAdminSession()
  router.replace({ path: '/login', query: { reason: 'timeout' } })
}
function resetInactivityTimer() {
  clearTimeout(inactivityTimer)
  inactivityTimer = setTimeout(logoutForInactivity, INACTIVITY_MS)
}
const activityEvents = ['pointerdown', 'keydown', 'scroll', 'touchstart']
onMounted(() => {
  compactQuery = window.matchMedia('(min-width: 861px) and (max-width: 1100px)')
  syncCompactViewport(compactQuery)
  compactQuery.addEventListener('change', syncCompactViewport)
  activityEvents.forEach(event => window.addEventListener(event, resetInactivityTimer, { passive: true }))
  resetInactivityTimer()
})
onUnmounted(() => {
  compactQuery?.removeEventListener('change', syncCompactViewport)
  activityEvents.forEach(event => window.removeEventListener(event, resetInactivityTimer))
  clearTimeout(inactivityTimer)
})
</script>

<style scoped>
.admin-shell {
  --sidebar-width: 244px;
  position: relative;
  min-height: 100dvh;
}

.admin-shell.nav-collapsed {
  --sidebar-width: 78px;
}

.ambient {
  position: fixed;
  z-index: -1;
  border-radius: 999px;
  filter: blur(2px);
  pointer-events: none;
}

.ambient-one {
  top: -16rem;
  left: 18%;
  width: 34rem;
  height: 34rem;
  background: radial-gradient(circle, rgba(122, 140, 220, 0.16), transparent 67%);
}

.ambient-two {
  right: -13rem;
  bottom: -17rem;
  width: 38rem;
  height: 38rem;
  background: radial-gradient(circle, rgba(174, 165, 197, 0.15), transparent 68%);
}

.skip-link {
  position: fixed;
  z-index: 100;
  top: 8px;
  left: 50%;
  padding: 10px 16px;
  border-radius: 10px;
  background: var(--miao-ink);
  color: #fff;
  text-decoration: none;
  transform: translate(-50%, -150%);
  transition: transform 180ms ease;
}

.skip-link:focus {
  transform: translate(-50%, 0);
}

.glass-sidebar {
  position: fixed;
  z-index: 30;
  inset: 14px auto 14px 14px;
  display: flex;
  width: var(--sidebar-width);
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.86);
  border-radius: 25px;
  background: rgba(247, 247, 250, 0.8);
  box-shadow: var(--miao-inner-light), 0 22px 60px rgba(52, 56, 78, 0.13);
  backdrop-filter: blur(32px) saturate(155%);
  -webkit-backdrop-filter: blur(32px) saturate(155%);
  transition: width 300ms var(--miao-ease), transform 300ms var(--miao-ease);
}

.brand {
  position: relative;
  display: flex;
  min-height: 76px;
  width: 100%;
  align-items: center;
  gap: 11px;
  padding: 14px 15px;
  border: 0;
  border-bottom: 1px solid rgba(62, 65, 78, 0.075);
  background: transparent;
  color: var(--miao-ink);
  cursor: pointer;
  text-align: left;
}

.brand:focus-visible {
  outline: 3px solid rgba(97, 116, 216, 0.22);
  outline-offset: -4px;
}

.brand-mark {
  position: relative;
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  overflow: visible;
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 14px;
  background: linear-gradient(150deg, #7789e6, #5d6fd0);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.3), 0 7px 17px rgba(80, 99, 194, 0.25);
  color: #fff;
}

.brand-ear {
  position: absolute;
  top: -2px;
  width: 13px;
  height: 13px;
  border-radius: 4px 9px 3px 7px;
  background: #7082df;
  transform: rotate(45deg);
}

.brand-ear-left { left: 4px; }
.brand-ear-right { right: 4px; }

.brand-face {
  position: relative;
  top: -1px;
  font-size: 22px;
  font-weight: 750;
  letter-spacing: -0.08em;
}

.brand-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  opacity: 1;
  transition: opacity 180ms ease;
}

.brand-copy strong {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.025em;
}

.brand-copy small,
.sidebar-footer small {
  margin-top: 2px;
  color: var(--miao-ink-tertiary);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.collapse-indicator {
  color: var(--miao-ink-tertiary);
  font-size: 11px;
  transition: transform 300ms var(--miao-ease);
}

.nav-label {
  margin: 16px 18px 7px;
  color: #9396a0;
  font-size: 10px;
  font-weight: 650;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  white-space: nowrap;
  transition: opacity 180ms ease;
}

.nav-scroll {
  min-height: 0;
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  padding-bottom: 8px;
  scrollbar-color: rgba(97, 116, 216, 0.24) transparent;
  scrollbar-width: thin;
}

.nav-scroll::-webkit-scrollbar { width: 5px; }
.nav-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(97, 116, 216, 0.24);
}

.glass-sidebar :deep(.el-menu) {
  border: 0;
  background: transparent;
}

.glass-sidebar :deep(.el-menu-item) {
  height: 43px;
  margin: 2px 9px;
  padding-inline: 13px !important;
  border: 1px solid transparent;
  border-radius: 12px;
  color: #5d606a;
  font-size: 13px;
  font-weight: 550;
  transition: color 180ms ease, background-color 180ms ease, border-color 180ms ease, transform 180ms var(--miao-ease), box-shadow 180ms ease;
}

.glass-sidebar :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.58);
  color: var(--miao-ink);
  transform: translateX(1px);
}

.glass-sidebar :deep(.el-menu-item.is-active) {
  border-color: rgba(255, 255, 255, 0.72);
  background: linear-gradient(135deg, rgba(235, 238, 255, 0.92), rgba(245, 245, 251, 0.78));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.88), 0 5px 14px rgba(80, 96, 177, 0.1);
  color: var(--miao-accent-deep);
}

.glass-sidebar :deep(.el-menu-item .el-icon) {
  width: 20px;
  margin-right: 10px;
  font-size: 17px;
}

.sidebar-footer {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  margin: auto 10px 10px;
  padding: 12px;
  border: 1px solid rgba(255, 255, 255, 0.68);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.42);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.status-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: #63a384;
  box-shadow: 0 0 0 4px rgba(99, 163, 132, 0.12);
}

.status-dot.session-dot {
  background: var(--miao-accent);
  box-shadow: 0 0 0 4px rgba(97, 116, 216, 0.11);
}

.sidebar-footer-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  white-space: nowrap;
}

.sidebar-footer-copy strong {
  color: #4b4e57;
  font-size: 11px;
  font-weight: 600;
}

.nav-collapsed .brand-copy,
.nav-collapsed .nav-label,
.nav-collapsed .sidebar-footer-copy {
  visibility: hidden;
  opacity: 0;
}

.nav-collapsed .collapse-indicator {
  display: none;
}

.nav-collapsed .glass-sidebar :deep(.el-menu-item) {
  justify-content: center;
  padding: 0 !important;
}

.nav-collapsed .glass-sidebar :deep(.el-menu-item .el-icon) {
  margin: 0;
}

.nav-collapsed .sidebar-footer {
  justify-content: center;
  padding-inline: 0;
}

.workspace {
  min-width: 0;
  min-height: 100dvh;
  margin-left: calc(var(--sidebar-width) + 28px);
  transition: margin-left 300ms var(--miao-ease);
}

.glass-topbar {
  position: sticky;
  z-index: 20;
  top: 14px;
  display: flex;
  height: 68px;
  align-items: center;
  justify-content: space-between;
  margin: 14px 16px 0 0;
  padding: 0 11px 0 21px;
  border: 1px solid rgba(255, 255, 255, 0.84);
  border-radius: 20px;
  background: rgba(249, 249, 251, 0.69);
  box-shadow: var(--miao-inner-light), 0 12px 34px rgba(49, 53, 74, 0.09);
  backdrop-filter: blur(28px) saturate(150%);
  -webkit-backdrop-filter: blur(28px) saturate(150%);
}

.page-heading {
  min-width: 0;
}

.page-heading h1 {
  margin: 1px 0 0;
  overflow: hidden;
  color: var(--miao-ink);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.035em;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-kicker {
  color: var(--miao-ink-tertiary);
  font-size: 9px;
  font-weight: 650;
  letter-spacing: 0.11em;
  text-transform: uppercase;
}

.topbar-actions,
.session-state,
.account-chip {
  display: flex;
  align-items: center;
}

.topbar-actions {
  gap: 10px;
}

.session-state {
  gap: 8px;
  padding: 9px 12px;
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.44);
  color: #656872;
  font-size: 11px;
  font-weight: 550;
}

.account-chip {
  min-height: 48px;
  gap: 10px;
  padding: 5px 10px 5px 6px;
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.54);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8), 0 2px 8px rgba(50, 54, 76, 0.05);
  color: var(--miao-ink);
  cursor: pointer;
  transition: transform 180ms var(--miao-ease), background-color 180ms ease, box-shadow 180ms ease;
}

.account-chip:hover {
  background: rgba(255, 255, 255, 0.78);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.92), 0 4px 12px rgba(50, 54, 76, 0.08);
  transform: translateY(-1px);
}

.account-chip:active {
  transform: scale(0.98);
}

.account-avatar {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 11px;
  background: linear-gradient(145deg, #8292e5, #6274d5);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.28);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.account-copy {
  display: flex;
  min-width: 72px;
  flex-direction: column;
  text-align: left;
}

.account-copy strong {
  overflow: hidden;
  max-width: 110px;
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
}

.account-copy small {
  margin-top: 1px;
  color: var(--miao-ink-tertiary);
  font-size: 10px;
}

.mobile-menu-button {
  display: none;
  width: 40px;
  height: 40px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.54);
  color: var(--miao-ink);
}

.content-area {
  padding: 20px 16px 28px 0;
  outline: none;
}

.content-frame {
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
}

.content-frame > * {
  animation: page-arrive 220ms var(--miao-ease) both;
}

@keyframes page-arrive {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .content-frame > * { animation: none; }
}

.mobile-scrim {
  position: fixed;
  z-index: 25;
  inset: 0;
  border: 0;
  background: rgba(38, 41, 52, 0.18);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

@media (max-width: 1100px) {
  .admin-shell {
    --sidebar-width: 78px;
  }

  .brand-copy,
  .nav-label,
  .sidebar-footer-copy,
  .collapse-indicator {
    display: none;
  }

  .glass-sidebar :deep(.el-menu-item) {
    justify-content: center;
    padding: 0 !important;
  }

  .glass-sidebar :deep(.el-menu-item .el-icon) {
    margin: 0;
  }

  .sidebar-footer {
    justify-content: center;
    padding-inline: 0;
  }
}

@media (max-width: 860px) {
  .admin-shell,
  .admin-shell.nav-collapsed {
    --sidebar-width: 0px;
  }

  .glass-sidebar {
    width: min(270px, calc(100vw - 34px));
    transform: translateX(calc(-100% - 24px));
  }

  .mobile-nav-open .glass-sidebar {
    transform: translateX(0);
  }

  .mobile-nav-open .brand-copy,
  .mobile-nav-open .nav-label,
  .mobile-nav-open .sidebar-footer-copy,
  .mobile-nav-open .collapse-indicator {
    display: flex;
    visibility: visible;
    opacity: 1;
  }

  .mobile-nav-open .nav-label,
  .mobile-nav-open .collapse-indicator {
    display: block;
  }

  .mobile-nav-open .glass-sidebar :deep(.el-menu-item) {
    justify-content: flex-start;
    padding-inline: 13px !important;
  }

  .mobile-nav-open .glass-sidebar :deep(.el-menu-item .el-icon) {
    margin-right: 10px;
  }

  .mobile-nav-open .sidebar-footer {
    justify-content: flex-start;
    padding-inline: 12px;
  }

  .workspace {
    margin-left: 0;
  }

  .glass-topbar {
    top: 10px;
    height: 64px;
    margin: 10px 10px 0;
    padding-left: 9px;
  }

  .mobile-menu-button {
    display: grid;
    margin-right: 10px;
  }

  .page-heading {
    flex: 1;
  }

  .content-area {
    padding: 16px 10px 24px;
  }
}

@media (max-width: 560px) {
  .page-kicker,
  .session-state,
  .account-copy,
  .account-chip > .el-icon {
    display: none;
  }

  .page-heading h1 {
    font-size: 17px;
  }

  .account-chip {
    min-height: 42px;
    padding: 3px;
    border-radius: 13px;
  }

  .account-avatar {
    width: 34px;
    height: 34px;
    border-radius: 10px;
  }
}
</style>
