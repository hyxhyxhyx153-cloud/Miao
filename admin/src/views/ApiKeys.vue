<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div>
          <div class="title">API Key 管理</div>
          <div class="subtitle">集中管理模型提供商凭据、连通性和 Token 告警。</div>
        </div>
        <el-button type="primary" @click="openAdd">+ 新增 Key</el-button>
      </div>
    </template>

    <el-alert
      class="page-alert"
      title="API Key 统一由后台管理。连接测试是可选项；无外网时可以先保存，恢复网络后再测试。"
      type="info"
      :closable="false"
    />

    <el-table :data="keys" stripe v-loading="loading">
      <el-table-column label="提供商" width="140">
        <template #default="{row}">
          <el-tag :type="providerColor(row.provider)">{{ providerName(row.provider) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Key 预览" prop="api_key_preview" width="160" />
      <el-table-column label="Base URL" min-width="180">
        <template #default="{row}">
          <span class="muted mono">{{ row.base_url || '官方默认' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{row}">
          <el-switch v-model="row.is_active" @change="toggleKey(row)" />
        </template>
      </el-table-column>
      <el-table-column label="备注" prop="note" min-width="120" />
      <el-table-column label="今日 Token" width="130">
        <template #default="{row}">
          <el-tag :type="row.alert_threshold && Number(row.today_tokens) >= Number(row.alert_threshold) ? 'danger' : 'info'">
            {{ Number(row.today_tokens || 0).toLocaleString() }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="连通性" width="110">
        <template #default="{row}">
          <span v-if="row._testResult === 'ok'" class="status-text status-ok">✓ 正常</span>
          <span v-else-if="row._testResult === 'fail'" class="status-text status-fail">✗ 失败</span>
          <span v-else class="status-text status-idle">未测试</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{row}">
          <el-button size="small" :loading="row._testing" @click="testKey(row)">测试</el-button>
          <el-button size="small" :loading="row._modelsLoading" @click="showModels(row)">模型</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="delKey(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="showDialog" :title="editId ? '编辑 API Key' : '新增 API Key'" width="480px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="提供商">
        <el-select v-model="form.provider" :disabled="!!editId" class="full-width">
          <el-option v-for="p in providers" :key="p.value" :label="p.label" :value="p.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="API Key">
        <el-input v-model="form.apiKey" show-password placeholder="sk-..." />
        <div v-if="editId" class="form-hint">留空表示保留现有密钥。</div>
        <div v-if="form.provider === 'gpt-image'" class="form-hint">
          该凭据只用于图片生成，不会出现在聊天模型列表中。
        </div>
      </el-form-item>
      <el-form-item label="Base URL">
        <el-input v-model="form.baseUrl" placeholder="例如 https://gateway.example.com/；留空使用官方默认地址" />
        <div class="form-hint">
          地址会原样保存（仅去除首尾空格），不会改动末尾斜杠或路径。若根地址返回的是站点页面而非 API，运行时会临时兼容尝试 /v1，但不会写回或改动这里的值。
        </div>
      </el-form-item>
      <el-form-item label="连接测试">
        <el-button :loading="formTesting" :disabled="!canTestForm" @click="testFormCredentials">
          {{ editId && !form.apiKey.trim() ? '测试现有 Key' : '测试并获取模型列表' }}
        </el-button>
        <span v-if="formTestState === 'ok'" class="validation-ok">✓ 测试通过</span>
        <div v-else-if="formTestState === 'fail'" class="validation-error">{{ formTestMessage }}</div>
        <div v-if="editId && !form.apiKey.trim() && baseUrlChanged" class="form-hint">
          Base URL 已修改：请先保存，再从列表中测试；或重新输入 Key 立即测试。
        </div>
        <div class="form-hint">测试失败不会影响保存；请检查服务器网络、Base URL 或代理配置。</div>
      </el-form-item>
      <el-form-item v-if="availableModels.length" label="可用模型">
        <el-select v-model="previewModel" filterable placeholder="共获取到可用模型" class="full-width">
          <el-option v-for="model in availableModels" :key="model.id" :label="model.displayName" :value="model.id" />
        </el-select>
        <div class="form-hint">已获取 {{ availableModels.length }} 个模型</div>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.note" placeholder="主Key / 备用Key..." />
      </el-form-item>
      <el-form-item label="Token 告警">
        <el-input-number v-model="form.alertThreshold" :min="0" :step="10000" controls-position="right" class="full-width" />
        <div class="form-hint">今日用量达到此值时在列表标红；0 表示不告警。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="showDialog=false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="modelsVisible" :title="`${providerName(modelsProvider)} 可用模型`" width="680px">
    <el-input v-model="modelSearch" class="dialog-search" prefix-icon="Search" clearable placeholder="筛选模型 ID 或名称" />
    <el-table :data="filteredModels" max-height="430" stripe v-loading="modelsLoading">
      <el-table-column prop="id" label="模型 ID" min-width="280" />
      <el-table-column prop="displayName" label="显示名称" min-width="220" />
      <el-table-column label="创建时间" width="170"><template #default="{ row }">{{ formatModelTime(row.createdAt) }}</template></el-table-column>
    </el-table>
    <el-empty v-if="!modelsLoading && !filteredModels.length" description="该 Key 未返回模型列表" />
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'
import { normalizeBaseUrl } from '../utils/baseUrl.js'

const keys = ref([]), loading = ref(false)
const showDialog = ref(false), editId = ref(null)
const form = ref({ provider: 'anthropic', apiKey: '', baseUrl: '', note: '', alertThreshold: 0 })
const formTesting = ref(false), formValidated = ref(false), saving = ref(false)
const formTestState = ref('idle'), formTestMessage = ref('')
const availableModels = ref([]), previewModel = ref('')
const modelsVisible = ref(false), modelsLoading = ref(false), modelsProvider = ref('')
const rowModels = ref([]), modelSearch = ref('')
let validatedFingerprint = ''
let originalBaseUrl = ''
const filteredModels = computed(() => {
  const query = modelSearch.value.trim().toLowerCase()
  return query ? rowModels.value.filter(model => `${model.id} ${model.displayName}`.toLowerCase().includes(query)) : rowModels.value
})
const baseUrlChanged = computed(() => normalizeBaseUrl(form.value.baseUrl) !== originalBaseUrl)
const canTestForm = computed(() => Boolean(
  form.value.apiKey.trim() || (editId.value && !baseUrlChanged.value)
))

const providers = [
  { value: 'anthropic', label: 'Anthropic (Claude)' },
  { value: 'openai',    label: 'OpenAI (GPT)' },
  { value: 'gpt-image', label: 'GPT Image 2（图片）' },
  { value: 'deepseek',  label: 'DeepSeek' },
  { value: 'qwen',      label: '阿里云 通义千问' },
  { value: 'zhipu',     label: '智谱 GLM' },
]

function providerName(v) { return providers.find(p => p.value === v)?.label || v }
function providerColor(v) {
  return { anthropic: '', openai: 'success', 'gpt-image': 'success', deepseek: 'warning', qwen: 'danger', zhipu: 'info' }[v] || ''
}

async function load() {
  loading.value = true
  try {
    const rows = await http.get('/admin/api-keys')
    keys.value = rows.map(r => ({ ...r, _testing: false, _testResult: null, _modelsLoading: false }))
  }
  catch (e) { ElMessage.error(e) }
  finally { loading.value = false }
}

async function testKey(row) {
  row._testing = true
  row._testResult = null
  try {
    await http.post(`/admin/api-keys/${row.id}/test`)
    row._testResult = 'ok'
    ElMessage.success(`${providerName(row.provider)} Key 连通正常`)
  } catch (e) {
    row._testResult = 'fail'
    ElMessage.error(`${providerName(row.provider)} Key 测试失败：${e}`)
  } finally {
    row._testing = false
  }
}

function formFingerprint() {
  return JSON.stringify([form.value.provider, form.value.apiKey, form.value.baseUrl])
}

function resetValidation() {
  formValidated.value = false
  formTestState.value = 'idle'
  formTestMessage.value = ''
  availableModels.value = []
  previewModel.value = ''
  validatedFingerprint = ''
}

function openAdd() {
  editId.value = null
  originalBaseUrl = ''
  form.value = { provider: 'anthropic', apiKey: '', baseUrl: '', note: '', alertThreshold: 0 }
  resetValidation()
  showDialog.value = true
}

async function openEdit(row) {
  editId.value = row.id
  originalBaseUrl = normalizeBaseUrl(row.base_url || '')
  form.value = {
    provider: row.provider,
    apiKey: '',
    baseUrl: row.base_url || '',
    note: row.note || '',
    alertThreshold: Number(row.alert_threshold || 0),
  }
  resetValidation()
  showDialog.value = true
}

function validateLocalForm() {
  form.value.apiKey = form.value.apiKey.trim()
  form.value.baseUrl = normalizeBaseUrl(form.value.baseUrl)
  form.value.note = form.value.note.trim()

  if (!providers.some(provider => provider.value === form.value.provider)) {
    throw new Error('请选择有效的提供商')
  }
  if (!editId.value && !form.value.apiKey) {
    throw new Error('请输入 API Key')
  }
  if (form.value.baseUrl) {
    let url
    try {
      url = new URL(form.value.baseUrl)
    } catch {
      throw new Error('Base URL 格式不正确，请填写完整的 http:// 或 https:// 地址')
    }
    if (!['http:', 'https:'].includes(url.protocol)) {
      throw new Error('Base URL 仅支持 http:// 或 https:// 地址')
    }
  }
}

function friendlyConnectionError(error) {
  const message = String(error || '连接测试失败')
  if (/network error|timeout|timed out|connection|econn|enotfound|fetch failed|socket|aborted/i.test(message)) {
    return `暂时无法连接上游 API：${message}。仍可直接保存。`
  }
  return message
}

async function validateForm(showSuccess = false) {
  validateLocalForm()
  if (!form.value.apiKey) throw new Error('请输入要测试的 API Key')
  formTesting.value = true
  try {
    const result = await http.post('/admin/api-keys/validate', {
      provider: form.value.provider,
      apiKey: form.value.apiKey,
      baseUrl: form.value.baseUrl,
    })
    availableModels.value = result.models || []
    previewModel.value = availableModels.value[0]?.id || ''
    formValidated.value = true
    formTestState.value = 'ok'
    formTestMessage.value = ''
    validatedFingerprint = formFingerprint()
    if (showSuccess) ElMessage.success(`连接正常，获取到 ${availableModels.value.length} 个模型`)
    return result
  } catch (e) {
    resetValidation()
    formTestState.value = 'fail'
    formTestMessage.value = friendlyConnectionError(e)
    if (showSuccess) ElMessage.error(formTestMessage.value)
    throw e
  } finally {
    formTesting.value = false
  }
}

async function testFormCredentials() {
  try {
    validateLocalForm()
    if (editId.value && !form.value.apiKey) {
      formTesting.value = true
      await http.post(`/admin/api-keys/${editId.value}/test`)
      const result = await http.get(`/admin/api-keys/${editId.value}/models`)
      availableModels.value = result.models || []
      previewModel.value = availableModels.value[0]?.id || ''
      formValidated.value = true
      formTestState.value = 'ok'
      formTestMessage.value = ''
      validatedFingerprint = formFingerprint()
      ElMessage.success(`连接正常，获取到 ${availableModels.value.length} 个模型`)
      return
    }
    await validateForm(true)
  } catch (e) {
    if (formTestState.value !== 'fail') {
      formTestState.value = 'fail'
      formTestMessage.value = friendlyConnectionError(e)
      ElMessage.error(formTestMessage.value)
    }
  } finally {
    formTesting.value = false
  }
}

async function save() {
  saving.value = true
  try {
    validateLocalForm()
    if (editId.value) {
      await http.patch(`/admin/api-keys/${editId.value}`, {
        apiKey: form.value.apiKey || undefined,
        baseUrl: form.value.baseUrl,
        note: form.value.note,
        alertThreshold: Number(form.value.alertThreshold || 0),
      })
    } else {
      await http.post('/admin/api-keys', form.value)
    }
    showDialog.value = false
    ElMessage.success(formValidated.value ? '已保存，连接测试通过' : '已保存，可在网络可用后测试连接')
    load()
  } catch (e) { ElMessage.error(e) }
  finally { saving.value = false }
}

async function toggleKey(row) {
  try { await http.patch(`/admin/api-keys/${row.id}`, { isActive: row.is_active }) }
  catch (e) { ElMessage.error(e); row.is_active = !row.is_active }
}

async function showModels(row) {
  modelsVisible.value = true
  modelsLoading.value = true
  row._modelsLoading = true
  modelsProvider.value = row.provider
  modelSearch.value = ''
  rowModels.value = []
  try {
    const result = await http.get(`/admin/api-keys/${row.id}/models`)
    rowModels.value = result.models || []
  } catch (e) {
    ElMessage.error(`模型列表获取失败：${e}`)
  } finally {
    modelsLoading.value = false
    row._modelsLoading = false
  }
}

function formatModelTime(value) {
  if (!value) return '-'
  const timestamp = typeof value === 'number' && value < 1e12 ? value * 1000 : value
  const date = new Date(timestamp)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}

watch(() => [form.value.provider, form.value.apiKey, form.value.baseUrl], () => {
  if (formTestState.value !== 'idle' && validatedFingerprint !== formFingerprint()) resetValidation()
}, { deep: true })

async function delKey(row) {
  try {
    await ElMessageBox.confirm(`确认删除 ${providerName(row.provider)} 的 Key？`, '提示', { type: 'warning' })
    await http.delete(`/admin/api-keys/${row.id}`); ElMessage.success('已删除'); load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e) }
}

onMounted(load)
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.title {
  font-weight: 650;
}

.subtitle,
.muted,
.form-hint {
  color: var(--miao-ink-secondary);
  font-size: 12px;
}

.subtitle {
  margin-top: 4px;
}

.page-alert {
  margin-bottom: 16px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
}

.status-text {
  font-size: 12px;
  font-weight: 600;
}

.status-ok,
.validation-ok {
  color: #4f9074;
}

.status-fail {
  color: #b64e57;
}

.validation-error {
  width: 100%;
  margin-top: 6px;
  color: #b64e57;
  font-size: 12px;
  line-height: 1.5;
}

.status-idle {
  color: var(--miao-ink-tertiary);
}

.validation-ok {
  margin-left: 10px;
  font-size: 12px;
}

.full-width {
  width: 100%;
}

.form-hint {
  margin-top: 6px;
  line-height: 1.5;
}

.dialog-search {
  margin-bottom: 12px;
}

@media (max-width: 640px) {
  .header-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
