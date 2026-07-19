<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div>
          <div class="title">用户管理</div>
          <div class="subtitle">查看账号、使用统计，管理封禁状态与每日配额。</div>
        </div>
        <div class="actions">
          <el-input v-model="search" placeholder="搜索用户名、邮箱或昵称" clearable prefix-icon="Search" />
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table :data="pageUsers" stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column label="账号" min-width="220">
        <template #default="{ row }"><div>{{ row.nickname || '-' }}</div><div class="muted">{{ row.email }}</div></template>
      </el-table-column>
      <el-table-column label="今日配额" width="160">
        <template #default="{ row }">
          <el-progress :percentage="quotaPercentage(row)" :status="row.quota_used >= row.daily_quota ? 'exception' : undefined" :stroke-width="7" :show-text="false" />
          <span class="muted">{{ row.quota_used || 0 }} / {{ row.daily_quota || 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag :type="row.is_banned ? 'danger' : row.is_active ? 'success' : 'info'">{{ row.is_banned ? '已封禁' : row.is_active ? '正常' : '未激活' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="注册时间" width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
          <el-button size="small" @click.stop="openQuota(row)">配额</el-button>
          <el-button size="small" :type="row.is_banned ? 'success' : 'danger'" @click.stop="toggleBan(row)">{{ row.is_banned ? '解封' : '封禁' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination"><el-pagination v-model:current-page="page" :page-size="pageSize" :total="filteredUsers.length" layout="total, prev, pager, next" /></div>
  </el-card>

  <el-drawer v-model="detailVisible" title="用户详情" size="520px">
    <div v-loading="detailLoading">
      <template v-if="detail">
        <div class="profile">
          <el-avatar :size="54">{{ (detail.nickname || detail.username || '?').slice(0, 1) }}</el-avatar>
          <div><h3>{{ detail.nickname || detail.username }}</h3><div class="muted">@{{ detail.username }} · {{ detail.email }}</div></div>
        </div>
        <el-row :gutter="10" class="stats">
          <el-col :span="8"><div class="stat"><strong>{{ detail.conversation_count || 0 }}</strong><span>会话</span></div></el-col>
          <el-col :span="8"><div class="stat"><strong>{{ detail.message_count || 0 }}</strong><span>消息</span></div></el-col>
          <el-col :span="8"><div class="stat"><strong>{{ detail.memory_count || 0 }}</strong><span>记忆</span></div></el-col>
        </el-row>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="用户 ID"><span class="mono">{{ detail.id }}</span></el-descriptions-item>
          <el-descriptions-item label="账号状态"><el-tag :type="detail.is_banned ? 'danger' : 'success'">{{ detail.is_banned ? '已封禁' : '正常' }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="今日配额">{{ detail.quota_used || 0 }} / {{ detail.daily_quota || 0 }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ formatTime(detail.created_at) }}</el-descriptions-item>
          <el-descriptions-item label="最近更新">{{ formatTime(detail.updated_at) }}</el-descriptions-item>
        </el-descriptions>
        <div class="drawer-actions">
          <el-button @click="openQuota(detail)">调整配额</el-button>
          <el-button :type="detail.is_banned ? 'success' : 'danger'" @click="toggleBan(detail)">{{ detail.is_banned ? '解除封禁' : '封禁用户' }}</el-button>
        </div>
      </template>
      <el-empty v-else-if="!detailLoading" description="用户详情不可用" />
    </div>
  </el-drawer>

  <el-dialog v-model="quotaVisible" title="调整每日配额" width="400px">
    <el-form label-width="90px">
      <el-form-item label="用户">{{ editing?.username }}</el-form-item>
      <el-form-item label="每日配额"><el-input-number v-model="quota" :min="0" :max="100000" controls-position="right" /></el-form-item>
      <el-form-item label="今日已用">{{ editing?.quota_used || 0 }}</el-form-item>
    </el-form>
    <template #footer><el-button @click="quotaVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveQuota">保存</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'

const users = ref([])
const loading = ref(false)
const search = ref('')
const page = ref(1)
const pageSize = 20
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const quotaVisible = ref(false)
const editing = ref(null)
const quota = ref(50)
const saving = ref(false)

const filteredUsers = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return users.value
  return users.value.filter(user => [user.username, user.email, user.nickname].some(value => String(value || '').toLowerCase().includes(query)))
})
const pageUsers = computed(() => filteredUsers.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch(search, () => { page.value = 1 })

async function load() {
  loading.value = true
  try { users.value = await http.get('/admin/users', { params: { limit: 500, offset: 0 } }) }
  catch (error) { ElMessage.error(`用户列表加载失败：${error}`) }
  finally { loading.value = false }
}

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try { detail.value = await http.get(`/admin/users/${row.id}`) }
  catch (error) { ElMessage.error(`用户详情加载失败：${error}`) }
  finally { detailLoading.value = false }
}

function openQuota(row) { editing.value = row; quota.value = Number(row.daily_quota || 0); quotaVisible.value = true }
async function saveQuota() {
  saving.value = true
  try {
    await http.patch(`/admin/users/${editing.value.id}`, { dailyQuota: quota.value })
    updateUser(editing.value.id, { daily_quota: quota.value })
    if (detail.value?.id === editing.value.id) detail.value.daily_quota = quota.value
    quotaVisible.value = false
    ElMessage.success('每日配额已更新')
  } catch (error) { ElMessage.error(`配额更新失败：${error}`) }
  finally { saving.value = false }
}

async function toggleBan(row) {
  const next = !row.is_banned
  try {
    await ElMessageBox.confirm(`确认${next ? '封禁' : '解封'}用户 ${row.username}？`, '用户状态', { type: 'warning' })
    await http.patch(`/admin/users/${row.id}`, { isBanned: next })
    updateUser(row.id, { is_banned: next })
    if (detail.value?.id === row.id) detail.value.is_banned = next
    ElMessage.success(`用户已${next ? '封禁' : '解封'}`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(`状态更新失败：${error}`)
  }
}

function updateUser(id, patch) { const row = users.value.find(user => user.id === id); if (row) Object.assign(row, patch) }
function quotaPercentage(row) { return row.daily_quota > 0 ? Math.min(100, Math.round((row.quota_used || 0) / row.daily_quota * 100)) : 100 }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
onMounted(load)
</script>

<style scoped>
.header-row,.actions,.profile,.drawer-actions { display:flex; align-items:center; }
.header-row { justify-content:space-between; gap:16px; flex-wrap:wrap; }
.actions { gap:8px; width:360px; }
.title { font-weight:650; }
.subtitle,.muted { color:var(--miao-ink-secondary); font-size:12px; margin-top:3px; }
.pagination { display:flex; justify-content:flex-end; margin-top:16px; }
.profile { gap:14px; padding-bottom:18px; }
.profile h3 { margin:0 0 4px; }
.stats { margin-bottom:18px; }
.stat { display:flex; flex-direction:column; align-items:center; padding:14px; border:1px solid rgba(97,116,216,.09); border-radius:12px; background:rgba(237,240,255,.62); }
.stat strong { font-size:22px; color:var(--miao-accent-deep); font-variant-numeric:tabular-nums; }.stat span { color:var(--miao-ink-secondary); font-size:12px; }
.mono { font:12px ui-monospace,SFMono-Regular,Consolas,monospace; }
.drawer-actions { justify-content:flex-end; gap:8px; margin-top:18px; }
@media (max-width:640px) { .actions { width:100%; } .header-row { align-items:flex-start; } }
</style>
