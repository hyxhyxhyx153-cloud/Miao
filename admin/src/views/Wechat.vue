<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div><div class="title">微信绑定管理</div><div class="subtitle">Worker 在线状态来自当前服务进程，不再仅依赖数据库状态字段。</div></div>
        <div class="actions"><el-tag :type="offlineCount ? 'warning' : 'success'">在线 {{ onlineCount }} / {{ bindings.length }}</el-tag><el-button :loading="loading" @click="load">刷新</el-button></div>
      </div>
    </template>
    <el-alert class="page-alert" type="info" :closable="false" title="绑定凭据由用户在 App 内扫码获取。重启 Worker 不需要重新扫码；强制解绑后用户必须重新扫码。" />

    <el-table :data="bindings" stripe v-loading="loading">
      <el-table-column prop="username" label="用户" width="150" />
      <el-table-column label="Worker" width="130">
        <template #default="{ row }"><el-tag :type="workerType(row)">{{ workerLabel(row) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="数据库状态" width="120"><template #default="{ row }"><span class="mono">{{ row.worker_status || '-' }}</span></template></el-table-column>
      <el-table-column label="最近错误" min-width="240">
        <template #default="{ row }"><el-tooltip v-if="workerError(row)" :content="workerError(row)" placement="top"><span class="error-text">{{ truncate(workerError(row), 55) }}</span></el-tooltip><span v-else class="muted">无</span></template>
      </el-table-column>
      <el-table-column label="最近更新" width="180"><template #default="{ row }">{{ formatTime(row.updated_at) }}</template></el-table-column>
      <el-table-column label="绑定时间" width="180"><template #default="{ row }">{{ formatTime(row.bound_at || row.created_at) }}</template></el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }"><el-button size="small" :loading="row._restarting" @click="restart(row)">重启 Worker</el-button><el-button size="small" type="danger" :loading="row._unbinding" @click="unbind(row)">强制解绑</el-button></template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !bindings.length" description="暂无有效微信绑定" />
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'

const bindings = ref([])
const loading = ref(false)
const onlineCount = computed(() => bindings.value.filter(row => row.worker_live).length)
const offlineCount = computed(() => bindings.value.length - onlineCount.value)
let timer

async function load() {
  loading.value = true
  try {
    const rows = await http.get('/admin/wechat/bindings')
    bindings.value = rows.map(row => ({ ...row, _restarting: false, _unbinding: false }))
  } catch (error) { ElMessage.error(`微信绑定加载失败：${error}`) }
  finally { loading.value = false }
}

async function restart(row) {
  row._restarting = true
  try {
    await http.post(`/admin/wechat/bindings/${row.id}/restart`)
    ElMessage.success('Worker 已重新启动')
    await load()
  } catch (error) { ElMessage.error(`Worker 重启失败：${error}`) }
  finally { row._restarting = false }
}

async function unbind(row) {
  try {
    await ElMessageBox.confirm(`确认强制解绑用户 ${row.username}？用户需要重新扫码才能恢复。`, '强制解绑', { type: 'warning' })
    row._unbinding = true
    await http.delete(`/admin/wechat/bindings/${row.id}`)
    ElMessage.success('微信绑定已解除')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(`解绑失败：${error}`)
  } finally { row._unbinding = false }
}

function workerError(row) { return row.last_error || row.worker_error || row.error_message || '' }
function workerLabel(row) { if (row.worker_live) return '真实在线'; if (workerError(row) || row.worker_status === 'error') return '运行错误'; return '离线' }
function workerType(row) { return row.worker_live ? 'success' : workerError(row) || row.worker_status === 'error' ? 'danger' : 'info' }
function truncate(value, max) { return value.length > max ? `${value.slice(0, max)}…` : value }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }

onMounted(() => { load(); timer = setInterval(load, 30000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.header-row,.actions { display:flex; align-items:center; gap:8px; }.header-row { justify-content:space-between; gap:16px; flex-wrap:wrap; }
.title { font-weight:650; }.subtitle,.muted { color:var(--miao-ink-secondary); font-size:12px; margin-top:4px; }.error-text { color:#b64e57; font-size:12px; cursor:help; }.mono { font:12px ui-monospace,SFMono-Regular,Consolas,monospace; }
.page-alert { margin-bottom:16px; }
</style>
