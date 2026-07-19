<template>
  <el-row :gutter="16">
    <el-col :span="24" class="section">
      <el-card shadow="never">
        <template #header><div class="header-row"><div><div class="title">服务状态</div><div class="subtitle">健康检查只报告 API 服务；数据库与 Redis 不伪造健康结论。</div></div><el-button size="small" :loading="healthLoading" @click="checkHealth">刷新</el-button></div></template>
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="API Server"><el-tag :type="health.api ? 'success' : 'danger'">{{ health.api ? '运行中' : '异常' }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="PostgreSQL"><el-tag type="info">未提供独立探针</el-tag></el-descriptions-item>
          <el-descriptions-item label="Redis"><el-tag type="info">未提供独立探针</el-tag></el-descriptions-item>
          <el-descriptions-item label="服务器时间">{{ health.time }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-col>

    <el-col :span="24">
      <el-card shadow="never">
        <template #header>
          <div class="header-row">
            <div><div class="title">请求与错误日志</div><div class="subtitle">共加载最近 {{ rawLogs.length }} 条，错误 {{ errorCount }} 条；每 30 秒自动刷新。</div></div>
            <el-button size="small" :loading="loading" @click="loadLogs">刷新</el-button>
          </div>
        </template>
        <div class="filters">
          <el-input v-model="filters.search" clearable prefix-icon="Search" placeholder="用户名或邮箱" />
          <el-select v-model="filters.status" clearable placeholder="全部状态"><el-option label="成功" value="ok" /><el-option label="错误" value="error" /></el-select>
          <el-select v-model="filters.provider" clearable filterable placeholder="全部提供商"><el-option v-for="value in providerOptions" :key="value" :label="value" :value="value" /></el-select>
          <el-select v-model="filters.model" clearable filterable placeholder="全部模型"><el-option v-for="value in modelOptions" :key="value" :label="value" :value="value" /></el-select>
          <el-date-picker v-model="filters.range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" range-separator="至" />
          <el-button @click="resetFilters">清空</el-button>
        </div>

        <el-table :data="pageLogs" stripe size="small" v-loading="loading" :row-class-name="({ row }) => row.status === 'error' ? 'error-row' : ''" @row-click="openDetail">
          <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
          <el-table-column label="用户" width="170"><template #default="{ row }"><strong>{{ row.username || '未知' }}</strong><div class="muted">{{ row.user_email || '-' }}</div></template></el-table-column>
          <el-table-column label="会话" min-width="140"><template #default="{ row }">{{ row.conversation_title || '-' }}</template></el-table-column>
          <el-table-column label="模型" min-width="190"><template #default="{ row }"><el-tag size="small" effect="plain">{{ row.model_provider || '-' }}</el-tag><div class="muted mono">{{ row.model_id || '-' }}</div></template></el-table-column>
          <el-table-column label="Token" width="115"><template #default="{ row }">↑ {{ row.input_tokens || 0 }} / ↓ {{ row.output_tokens || 0 }}</template></el-table-column>
          <el-table-column label="耗时" width="90"><template #default="{ row }"><span :class="{ slow: row.duration_ms > 10000 }">{{ duration(row.duration_ms) }}</span></template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ok' ? 'success' : 'danger'">{{ row.status === 'ok' ? '成功' : '错误' }}</el-tag></template></el-table-column>
          <el-table-column label="错误摘要" min-width="220"><template #default="{ row }"><span v-if="row.error_msg" class="error-text">{{ truncate(row.error_msg, 70) }}</span><span v-else class="muted">-</span></template></el-table-column>
        </el-table>
        <el-empty v-if="!loading && !filteredLogs.length" description="当前筛选条件下没有日志" />
        <div class="pagination"><el-pagination v-model:current-page="page" :page-size="pageSize" :total="filteredLogs.length" layout="total, prev, pager, next" /></div>
      </el-card>
    </el-col>
  </el-row>

  <el-drawer v-model="detailVisible" title="请求详情" size="620px">
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-alert v-if="detail.error_msg" class="detail-alert" type="error" :closable="false" :title="detail.error_msg" />
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="时间" :span="2">{{ formatTime(detail.created_at) }}</el-descriptions-item>
          <el-descriptions-item label="用户">{{ detail.username || '未知' }}</el-descriptions-item><el-descriptions-item label="邮箱">{{ detail.user_email || '-' }}</el-descriptions-item>
          <el-descriptions-item label="提供商">{{ detail.model_provider || '-' }}</el-descriptions-item><el-descriptions-item label="模型">{{ detail.model_id || '-' }}</el-descriptions-item>
          <el-descriptions-item label="输入 Token">{{ detail.input_tokens || 0 }}</el-descriptions-item><el-descriptions-item label="输出 Token">{{ detail.output_tokens || 0 }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ duration(detail.duration_ms) }}</el-descriptions-item><el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
        </el-descriptions>
        <h4>用户请求</h4><el-input :model-value="detail.request_content || '（无内容）'" type="textarea" :rows="5" readonly />
        <h4>AI 响应</h4><el-input :model-value="detail.response_content || '（无内容）'" type="textarea" :rows="9" readonly />
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import http from '../http.js'

const health = ref({ api: false, time: '-' })
const healthLoading = ref(false)
const rawLogs = ref([])
const loading = ref(false)
const filters = ref({ search: '', status: '', provider: '', model: '', range: null })
const page = ref(1)
const pageSize = 25
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
let timer

const providerOptions = computed(() => [...new Set(rawLogs.value.map(row => row.model_provider).filter(Boolean))].sort())
const modelOptions = computed(() => [...new Set(rawLogs.value.map(row => row.model_id).filter(Boolean))].sort())
const errorCount = computed(() => rawLogs.value.filter(row => row.status === 'error').length)
const filteredLogs = computed(() => rawLogs.value.filter(row => {
  const query = filters.value.search.trim().toLowerCase()
  if (query && !`${row.username || ''} ${row.user_email || ''}`.toLowerCase().includes(query)) return false
  if (filters.value.status && row.status !== filters.value.status) return false
  if (filters.value.provider && row.model_provider !== filters.value.provider) return false
  if (filters.value.model && row.model_id !== filters.value.model) return false
  if (filters.value.range?.length === 2) {
    const time = new Date(row.created_at).getTime()
    if (time < new Date(filters.value.range[0]).getTime() || time > new Date(filters.value.range[1]).getTime()) return false
  }
  return true
}))
const pageLogs = computed(() => filteredLogs.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch(filters, () => { page.value = 1 }, { deep: true })

async function checkHealth() {
  healthLoading.value = true
  try {
    const response = await axios.get('/health')
    health.value = { api: response.data.status === 'ok', time: formatTime(response.data.time) }
  } catch (error) {
    health.value = { api: false, time: '-' }
    ElMessage.error(`健康检查失败：${error.message || error}`)
  } finally { healthLoading.value = false }
}

async function loadLogs() {
  loading.value = true
  try {
    const data = await http.get('/admin/logs', { params: { limit: 500, offset: 0 } })
    rawLogs.value = Array.isArray(data?.items) ? data.items : []
  } catch (error) { ElMessage.error(`请求日志加载失败：${error}`) }
  finally { loading.value = false }
}

async function openDetail(row) {
  detailVisible.value = true; detailLoading.value = true; detail.value = null
  try { detail.value = await http.get(`/admin/logs/${row.id}`) }
  catch (error) { ElMessage.error(`日志详情加载失败：${error}`) }
  finally { detailLoading.value = false }
}

function resetFilters() { filters.value = { search: '', status: '', provider: '', model: '', range: null } }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
function duration(value) { return value == null ? '-' : `${(Number(value) / 1000).toFixed(2)}s` }
function truncate(value, max) { return value.length > max ? `${value.slice(0, max)}…` : value }
onMounted(() => { checkHealth(); loadLogs(); timer = setInterval(loadLogs, 30000) })
onUnmounted(() => clearInterval(timer))
</script>

<style>
.error-row td { background:rgba(197,95,101,.065) !important; }
</style>
<style scoped>
.section { margin-bottom:16px; }.header-row { display:flex; align-items:center; justify-content:space-between; gap:16px; }.title { font-weight:650; }.subtitle,.muted { color:var(--miao-ink-secondary); font-size:12px; margin-top:3px; }
.filters { display:grid; grid-template-columns:180px 120px 140px 180px minmax(330px,1fr) auto; gap:8px; margin-bottom:14px; }.pagination { display:flex; justify-content:flex-end; margin-top:14px; }.mono { font-family:ui-monospace,SFMono-Regular,Consolas,monospace; }.slow,.error-text { color:#b64e57; } h4 { margin:18px 0 8px; color:var(--miao-ink); font-weight:650; }.detail-alert { margin-bottom:16px; }
@media (max-width:1100px) { .filters { grid-template-columns:repeat(2,minmax(180px,1fr)); } }
@media (max-width:600px) { .filters { grid-template-columns:1fr; } }
</style>
