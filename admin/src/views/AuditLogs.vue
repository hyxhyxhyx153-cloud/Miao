<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div>
          <div class="title">审计日志</div>
          <div class="subtitle">记录管理员对用户、密钥、配置和平台资源的变更操作，不提供删除入口。</div>
        </div>
        <div class="filters">
          <el-input v-model="action" clearable placeholder="按操作类型精确筛选" @keyup.enter="reload" />
          <el-button :loading="loading" @click="reload">查询</el-button>
        </div>
      </div>
    </template>
    <el-table :data="logs" stripe v-loading="loading">
      <el-table-column label="时间" width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
      <el-table-column label="管理员" width="150"><template #default="{ row }">{{ row.admin_username || row.admin_user_id || '系统' }}</template></el-table-column>
      <el-table-column prop="action" label="操作" width="190"><template #default="{ row }"><el-tag effect="plain">{{ row.action }}</el-tag></template></el-table-column>
      <el-table-column label="目标" min-width="220"><template #default="{ row }"><span class="mono">{{ row.target_type || '-' }} / {{ row.target_id || '-' }}</span></template></el-table-column>
      <el-table-column label="详情" min-width="260"><template #default="{ row }"><el-popover width="420" trigger="click"><pre class="detail">{{ pretty(row.details ?? row.metadata) }}</pre><template #reference><el-button link type="primary">查看详情</el-button></template></el-popover></template></el-table-column>
      <el-table-column prop="ip_address" label="来源 IP" width="150" />
    </el-table>
    <div class="pagination">
      <el-pagination v-model:current-page="page" :page-size="pageSize" :total="hasMore ? page * pageSize + 1 : (page - 1) * pageSize + logs.length" layout="prev, pager, next" @current-change="load" />
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../http.js'

const logs = ref([])
const loading = ref(false)
const action = ref('')
const page = ref(1)
const pageSize = 50
const hasMore = ref(false)

async function load() {
  loading.value = true
  try {
    const params = { limit: pageSize, offset: (page.value - 1) * pageSize }
    if (action.value.trim()) params.action = action.value.trim()
    const rows = await http.get('/admin/audit-logs', { params })
    logs.value = Array.isArray(rows) ? rows : []
    hasMore.value = logs.value.length === pageSize
  } catch (error) {
    ElMessage.error(`审计日志加载失败：${error}`)
  } finally {
    loading.value = false
  }
}

function reload() { page.value = 1; load() }
function pretty(value) { if (value == null) return '无附加详情'; if (typeof value === 'string') { try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value } } return JSON.stringify(value, null, 2) }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
onMounted(load)
</script>

<style scoped>
.header-row { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; }
.filters { display:flex; gap:8px; width:360px; }
.title { font-weight:650; }
.subtitle { color:var(--miao-ink-secondary); font-size:12px; margin-top:4px; }
.mono { font:12px ui-monospace,SFMono-Regular,Consolas,monospace; color:#50535d; }
.detail { margin:0; max-height:320px; overflow:auto; white-space:pre-wrap; word-break:break-word; }
.pagination { display:flex; justify-content:flex-end; margin-top:16px; }
@media (max-width:640px) { .filters { width:100%; } .filters .el-input { flex:1; } }
</style>
