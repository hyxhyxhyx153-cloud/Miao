<template>
  <main class="login-page">
    <div class="login-orb orb-one" aria-hidden="true" />
    <div class="login-orb orb-two" aria-hidden="true" />
    <div class="login-orb orb-three" aria-hidden="true" />

    <section class="login-shell" aria-labelledby="login-title">
      <div class="login-intro">
        <div class="product-mark" aria-hidden="true">
          <span class="mark-ear mark-ear-left" />
          <span class="mark-ear mark-ear-right" />
          <span>m</span>
        </div>
        <p class="eyebrow">Miao Console</p>
        <h1 id="login-title">欢迎回来</h1>
        <p class="intro-copy">登录管理中心，查看用户、模型与服务运行状态。</p>
      </div>

      <div class="login-panel">
        <div class="panel-highlight" aria-hidden="true" />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="管理员账号" prop="email">
            <el-input
              v-model="form.email"
              autocomplete="username"
              placeholder="用户名或邮箱"
              prefix-icon="User"
              size="large"
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              autocomplete="current-password"
              type="password"
              placeholder="输入密码"
              prefix-icon="Lock"
              show-password
              size="large"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-alert v-if="error" class="login-error" :title="String(error)" type="error" :closable="false" show-icon />
          <el-button class="login-button" type="primary" size="large" native-type="submit" :loading="loading" @click="submit">
            {{ loading ? '正在验证' : '登录管理中心' }}
          </el-button>
          <div class="security-note">
            <el-icon><Lock /></el-icon>
            <span>会话闲置 30 分钟后自动退出</span>
          </div>
        </el-form>
      </div>
    </section>

    <footer class="login-footer">Miao · 私有管理控制台</footer>
  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import http from '../http.js'
import { setAdminSession } from '../authSession.js'

const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)
const error = ref('')
const form = ref({ email: '', password: '' })
const rules = {
  email: [{ required: true, message: '请输入管理员用户名或邮箱', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit() {
  if (loading.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  error.value = ''
  try {
    const res = await http.post('/auth/login', form.value)
    if (res.user?.role !== 'admin') {
      error.value = '当前账号没有管理员权限'
      return
    }
    setAdminSession(res.accessToken, res.user)
    router.push('/')
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (route.query.reason === 'timeout') error.value = '会话因 30 分钟无操作已退出，请重新登录'
  if (route.query.reason === 'expired') error.value = '登录会话已失效，请重新登录'
})
</script>

<style scoped>
.login-page {
  position: relative;
  display: grid;
  min-height: 100dvh;
  place-items: center;
  overflow: hidden;
  padding: 40px 24px 66px;
  background:
    radial-gradient(circle at 18% 8%, rgba(130, 147, 224, 0.2), transparent 31rem),
    radial-gradient(circle at 86% 80%, rgba(179, 168, 201, 0.2), transparent 34rem),
    linear-gradient(145deg, #f6f5f2 0%, #eeeef0 100%);
}

.login-page::before {
  position: absolute;
  inset: 0;
  content: "";
  opacity: 0.18;
  pointer-events: none;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 180 180' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.92' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='.08'/%3E%3C/svg%3E");
}

.login-orb {
  position: absolute;
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72), 0 30px 70px rgba(60, 65, 94, 0.08);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  pointer-events: none;
}

.orb-one {
  top: 8%;
  right: 12%;
  width: 126px;
  height: 126px;
}

.orb-two {
  bottom: 12%;
  left: 9%;
  width: 74px;
  height: 74px;
}

.orb-three {
  top: 22%;
  left: 16%;
  width: 30px;
  height: 30px;
  background: rgba(114, 133, 218, 0.22);
}

.login-shell {
  position: relative;
  z-index: 1;
  display: grid;
  width: min(920px, 100%);
  grid-template-columns: minmax(250px, 0.9fr) minmax(360px, 1.1fr);
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.88);
  border-radius: 30px;
  background: rgba(248, 248, 250, 0.64);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.95), inset 0 0 0 1px rgba(255, 255, 255, 0.22), 0 32px 90px rgba(49, 54, 78, 0.15);
  backdrop-filter: blur(34px) saturate(150%);
  -webkit-backdrop-filter: blur(34px) saturate(150%);
}

.login-intro {
  display: flex;
  min-height: 520px;
  flex-direction: column;
  justify-content: flex-end;
  padding: 52px 46px;
  border-right: 1px solid rgba(58, 61, 72, 0.07);
  background:
    radial-gradient(circle at 25% 5%, rgba(255, 255, 255, 0.75), transparent 15rem),
    linear-gradient(155deg, rgba(227, 231, 250, 0.66), rgba(241, 240, 244, 0.54));
}

.product-mark {
  position: relative;
  display: grid;
  width: 62px;
  height: 62px;
  margin-bottom: auto;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.76);
  border-radius: 20px;
  background: linear-gradient(145deg, #7d8ee5, #6072d3);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.34), 0 13px 28px rgba(76, 94, 185, 0.25);
  color: #fff;
  font-size: 31px;
  font-weight: 760;
  letter-spacing: -0.09em;
}

.mark-ear {
  position: absolute;
  top: -3px;
  width: 17px;
  height: 17px;
  border-radius: 5px 11px 4px 8px;
  background: #7587e0;
  transform: rotate(45deg);
}

.mark-ear-left { left: 7px; }
.mark-ear-right { right: 7px; }

.eyebrow {
  margin: 0 0 9px;
  color: var(--miao-accent-deep);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.login-intro h1 {
  margin: 0;
  color: var(--miao-ink);
  font-size: clamp(38px, 5vw, 54px);
  font-weight: 740;
  letter-spacing: -0.06em;
  line-height: 1;
}

.intro-copy {
  max-width: 28ch;
  margin: 18px 0 0;
  color: var(--miao-ink-secondary);
  font-size: 14px;
  line-height: 1.7;
  text-wrap: pretty;
}

.login-panel {
  position: relative;
  display: flex;
  align-items: center;
  padding: 58px 56px;
  background: rgba(255, 255, 255, 0.48);
}

.panel-highlight {
  position: absolute;
  top: -80px;
  right: -50px;
  width: 230px;
  height: 230px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(139, 154, 224, 0.13), transparent 67%);
  pointer-events: none;
}

.login-panel :deep(.el-form) {
  position: relative;
  width: 100%;
}

.login-panel :deep(.el-form-item) {
  margin-bottom: 21px;
}

.login-panel :deep(.el-form-item__label) {
  padding-bottom: 7px;
  font-size: 12px;
  font-weight: 620;
}

.login-panel :deep(.el-input__wrapper) {
  min-height: 50px;
  padding-inline: 14px;
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.75) !important;
}

.login-error {
  margin: -4px 0 18px;
}

.login-button {
  width: 100%;
  min-height: 50px;
  margin-top: 4px;
  border-radius: 13px;
  font-size: 14px;
  font-weight: 650;
}

.security-note {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 17px;
  color: var(--miao-ink-tertiary);
  font-size: 10px;
}

.login-footer {
  position: absolute;
  z-index: 1;
  bottom: 22px;
  color: #9699a1;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

@media (max-width: 760px) {
  .login-page {
    display: block;
    padding: 18px 14px 62px;
  }

  .login-shell {
    grid-template-columns: 1fr;
    border-radius: 25px;
  }

  .login-intro {
    min-height: auto;
    padding: 31px 28px 30px;
    border-right: 0;
    border-bottom: 1px solid rgba(58, 61, 72, 0.07);
  }

  .product-mark {
    width: 50px;
    height: 50px;
    margin-bottom: 48px;
    border-radius: 16px;
    font-size: 25px;
  }

  .mark-ear {
    width: 14px;
    height: 14px;
  }

  .login-intro h1 {
    font-size: 38px;
  }

  .login-panel {
    padding: 34px 28px 38px;
  }

  .login-footer {
    position: static;
    margin-top: 22px;
    text-align: center;
  }
}

@media (max-width: 420px) {
  .login-page {
    padding-inline: 10px;
  }

  .login-intro,
  .login-panel {
    padding-inline: 22px;
  }
}
</style>
