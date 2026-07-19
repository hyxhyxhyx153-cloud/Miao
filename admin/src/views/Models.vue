<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div>
          <div class="title">模型管理</div>
          <div class="subtitle">模型启用状态和元数据由服务端持久化；可用性取决于对应提供商是否有启用的 API Key。</div>
        </div>
        <div class="actions">
          <el-button :loading="discovering" :disabled="deleting" @click="discoverModels">从 Key 获取模型</el-button>
          <el-button
            type="danger"
            plain
            :disabled="!selectedModels.length || deleting"
            :loading="deletingMode === 'selected'"
            @click="deleteSelectedDisabled"
          >批量删除{{ selectedModels.length ? `（${selectedModels.length}）` : '' }}</el-button>
          <el-button
            type="danger"
            :disabled="!disabledCount || deleting"
            :loading="deletingMode === 'all'"
            @click="deleteAllDisabled"
          >一键删除未启用模型</el-button>
          <el-button :loading="loading" :disabled="deleting" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="summary">
      <el-tag type="success">已启用 {{ enabledCount }}</el-tag>
      <el-tag type="warning">未启用 {{ disabledCount }}</el-tag>
      <el-tag type="info">已配置 {{ models.length }}</el-tag>
      <el-tag :type="missingProviders.length ? 'warning' : 'success'">{{ missingProviders.length ? `缺少 Key：${missingProviders.join('、')}` : '提供商 Key 正常' }}</el-tag>
    </div>

    <el-table :data="models" stripe v-loading="loading" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="48" :selectable="canSelectModel" />
      <el-table-column label="服务状态" width="110">
        <template #default="{ row }"><el-tag :type="providerReady(row.provider) && row.is_enabled ? 'success' : 'info'">{{ providerReady(row.provider) && row.is_enabled ? '可用' : providerReady(row.provider) ? '已停用' : '缺少 Key' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="提供商" width="130"><template #default="{ row }"><el-tag effect="plain" :type="tagColor(row.provider)">{{ providerLabel(row.provider) }}</el-tag></template></el-table-column>
      <el-table-column prop="model_id" label="模型 ID" min-width="220"><template #default="{ row }"><span class="mono">{{ row.model_id }}</span></template></el-table-column>
      <el-table-column label="显示名称" min-width="170"><template #default="{ row }">{{ row.display_name || row.model_id }}</template></el-table-column>
      <el-table-column label="能力" width="110"><template #default="{ row }"><el-tag v-if="row.supports_vision" type="success" size="small">Vision</el-tag><span v-else class="muted">文本</span></template></el-table-column>
      <el-table-column prop="description" label="说明" min-width="220"><template #default="{ row }"><span class="muted">{{ row.description || '未填写' }}</span></template></el-table-column>
      <el-table-column label="启用" width="80"><template #default="{ row }"><el-switch v-model="row.is_enabled" :loading="row._saving" @change="toggle(row)" /></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button size="small" @click="openEdit(row)">编辑</el-button></template></el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="editVisible" title="编辑模型元数据" width="520px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="模型 ID"><el-input v-model="form.modelId" disabled /></el-form-item>
      <el-form-item label="提供商"><el-select v-model="form.provider" style="width:100%"><el-option v-for="provider in providers" :key="provider.value" :label="provider.label" :value="provider.value" /></el-select></el-form-item>
      <el-form-item label="显示名称"><el-input v-model="form.displayName" /></el-form-item>
      <el-form-item label="视觉能力"><el-switch v-model="form.supportsVision" /></el-form-item>
      <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      <el-form-item label="启用"><el-switch v-model="form.isEnabled" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="editVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button></template>
  </el-dialog>

  <el-dialog v-model="discoverVisible" title="从 API Key 发现模型" width="720px">
    <el-alert v-if="discoverErrors.length" type="warning" :closable="false" :title="discoverErrors.join('；')" style="margin-bottom:12px" />
    <el-table :data="discovered" max-height="430" stripe>
      <el-table-column prop="provider" label="提供商" width="120" />
      <el-table-column prop="id" label="模型 ID" min-width="250" />
      <el-table-column prop="displayName" label="名称" min-width="180" />
      <el-table-column label="配置状态" width="100"><template #default="{ row }"><el-tag :type="configuredIds.has(row.id) ? 'success' : 'info'">{{ configuredIds.has(row.id) ? '已配置' : '未配置' }}</el-tag></template></el-table-column>
    </el-table>
    <template #footer><el-button @click="discoverVisible=false">关闭</el-button><span class="muted">发现结果用于核对；请在服务端模型配置中选择需要开放的模型。</span></template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'

const providers = [
  { value: 'anthropic', label: 'Anthropic' }, { value: 'openai', label: 'OpenAI' },
  { value: 'deepseek', label: 'DeepSeek' }, { value: 'qwen', label: '通义千问' }, { value: 'zhipu', label: '智谱 GLM' },
]
const models = ref([])
const selectedModels = ref([])
const keys = ref([])
const loading = ref(false)
const saving = ref(false)
const deletingMode = ref('')
const deleting = computed(() => Boolean(deletingMode.value))
const editVisible = ref(false)
const form = ref({ modelId: '', provider: '', displayName: '', supportsVision: false, description: '', isEnabled: true })
const discovering = ref(false)
const discoverVisible = ref(false)
const discovered = ref([])
const discoverErrors = ref([])
const enabledCount = computed(() => models.value.filter(model => model.is_enabled).length)
const disabledCount = computed(() => models.value.filter(model => !model.is_enabled).length)
const configuredIds = computed(() => new Set(models.value.map(model => model.model_id)))
const missingProviders = computed(() => [...new Set(models.value.filter(model => !providerReady(model.provider)).map(model => providerLabel(model.provider)))])

async function load() {
  loading.value = true
  try {
    const [modelRows, keyRows] = await Promise.all([http.get('/admin/models'), http.get('/admin/api-keys')])
    models.value = modelRows.map(row => ({ ...row, _saving: false }))
    selectedModels.value = []
    keys.value = keyRows
  } catch (error) { ElMessage.error(`模型配置加载失败：${error}`) }
  finally { loading.value = false }
}

function providerReady(provider) { return keys.value.some(key => key.provider === provider && key.is_active) }
function providerLabel(value) { return providers.find(provider => provider.value === value)?.label || value || '未设置' }
function tagColor(value) { return { anthropic: '', openai: 'success', deepseek: 'warning', qwen: 'danger', zhipu: 'info' }[value] || 'info' }
function canSelectModel() { return !deleting.value }
function handleSelectionChange(selection) { selectedModels.value = selection }

async function toggle(row) {
  row._saving = true
  try {
    await http.patch(`/admin/models/${encodeURIComponent(row.model_id)}`, payload(row))
    ElMessage.success(`${row.display_name || row.model_id} 已${row.is_enabled ? '启用' : '停用'}`)
  } catch (error) {
    row.is_enabled = !row.is_enabled
    ElMessage.error(`模型状态更新失败：${error}`)
  } finally { row._saving = false }
}

function deletionSummary(result) {
  const skipped = Array.isArray(result.skipped) ? result.skipped : []
  const inUse = skipped.filter(item => item.reason === 'in_use')
  const enabled = skipped.filter(item => item.reason === 'enabled')
  const missing = skipped.filter(item => item.reason === 'not_found')
  const parts = [`已删除 ${result.deletedCount || 0} 个模型`]
  if (inUse.length) {
    const conversations = inUse.reduce((sum, item) => sum + Number(item.conversationCount || 0), 0)
    parts.push(`${inUse.length} 个被 ${conversations} 个历史会话引用，已跳过`)
  }
  if (enabled.length) parts.push(`${enabled.length} 个仍处于启用状态，已跳过`)
  if (missing.length) parts.push(`${missing.length} 个已不存在`)
  return { message: parts.join('；'), hasSkipped: skipped.length > 0 }
}

async function runDeleteDisabled(body, mode) {
  deletingMode.value = mode
  try {
    const result = await http.post('/admin/models/delete-disabled', body)
    const summary = deletionSummary(result)
    if (summary.hasSkipped) ElMessage.warning({ message: summary.message, duration: 6000 })
    else ElMessage.success(summary.message)
    await load()
  } catch (error) {
    ElMessage.error(`删除失败：${error}`)
  } finally {
    deletingMode.value = ''
  }
}

async function deleteSelectedDisabled() {
  const modelIds = selectedModels.value.map(model => model.model_id)
  if (!modelIds.length) return
  try {
    await ElMessageBox.confirm(
      `确认处理选中的 ${modelIds.length} 个模型？仅删除已停用且未被历史会话引用的模型，启用模型会自动跳过。`,
      '批量删除模型',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch { return }
  await runDeleteDisabled({ modelIds }, 'selected')
}

async function deleteAllDisabled() {
  if (!disabledCount.value) return
  try {
    await ElMessageBox.confirm(
      `确认清理当前 ${disabledCount.value} 个未启用模型？仍被历史会话引用的模型会保留。`,
      '一键删除未启用模型',
      { confirmButtonText: '确认清理', cancelButtonText: '取消', type: 'warning' },
    )
  } catch { return }
  await runDeleteDisabled({ deleteAllDisabled: true }, 'all')
}

function openEdit(row) {
  form.value = { modelId: row.model_id, provider: row.provider || '', displayName: row.display_name || '', supportsVision: Boolean(row.supports_vision), description: row.description || '', isEnabled: Boolean(row.is_enabled) }
  editVisible.value = true
}

function payload(row) { return { provider: row.provider, displayName: row.display_name, supportsVision: Boolean(row.supports_vision), description: row.description, isEnabled: Boolean(row.is_enabled) } }
async function saveEdit() {
  if (!form.value.provider || !form.value.displayName.trim()) { ElMessage.warning('请填写提供商和显示名称'); return }
  saving.value = true
  try {
    const updated = await http.patch(`/admin/models/${encodeURIComponent(form.value.modelId)}`, {
      provider: form.value.provider, displayName: form.value.displayName.trim(), supportsVision: form.value.supportsVision,
      description: form.value.description.trim(), isEnabled: form.value.isEnabled,
    })
    const index = models.value.findIndex(model => model.model_id === form.value.modelId)
    if (index >= 0) models.value[index] = { ...updated, _saving: false }
    editVisible.value = false
    ElMessage.success('模型元数据已保存')
  } catch (error) { ElMessage.error(`保存失败：${error}`) }
  finally { saving.value = false }
}

async function discoverModels() {
  discovering.value = true
  discovered.value = []
  discoverErrors.value = []
  try {
    const activeKeys = keys.value.filter(key => key.is_active)
    if (!activeKeys.length) { ElMessage.warning('没有启用的 API Key'); return }
    const results = await Promise.allSettled(activeKeys.map(async key => {
      const result = await http.get(`/admin/api-keys/${key.id}/models`)
      return (result.models || []).map(model => ({ ...model, provider: result.provider }))
    }))
    const unique = new Map()
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') result.value.forEach(model => unique.set(`${model.provider}:${model.id}`, model))
      else discoverErrors.value.push(`${providerLabel(activeKeys[index].provider)}：${result.reason}`)
    })
    discovered.value = [...unique.values()]
    await load()
    discoverVisible.value = true
    if (!discovered.value.length && discoverErrors.value.length) ElMessage.error('所有提供商的模型列表获取均失败')
  } finally { discovering.value = false }
}

onMounted(load)
</script>

<style scoped>
.header-row,.actions,.summary { display:flex; align-items:center; gap:8px; }.header-row { justify-content:space-between; flex-wrap:wrap; gap:16px; }
.title { font-weight:650; }.subtitle,.muted { color:var(--miao-ink-secondary); font-size:12px; margin-top:4px; }.summary { margin-bottom:16px; flex-wrap:wrap; }
.mono { font:12px ui-monospace,SFMono-Regular,Consolas,monospace; }
@media (max-width:640px) { .actions { width:100%; flex-wrap:wrap; } }
</style>
