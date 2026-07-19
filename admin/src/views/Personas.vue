<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div>
          <div class="title">人格管理</div>
          <div class="subtitle">所有人格均可修改；系统内置人格可编辑，但不可删除。</div>
        </div>
        <el-button type="primary" @click="openAdd">+ 新增人格</el-button>
      </div>
    </template>

    <el-table :data="personas" stripe v-loading="loading">
      <el-table-column prop="name" label="名称" width="120" />
      <el-table-column prop="description" label="描述" min-width="200" />
      <el-table-column label="类型" width="120">
        <template #default="{row}">
          <el-tag :type="row.is_builtin ? 'info' : 'success'" size="small">
            {{ isProtected(row) ? '系统内置' : row.is_builtin ? '平台人格' : '用户人格' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="系统提示词" min-width="240">
        <template #default="{row}">
          <el-tooltip :content="row.system_prompt" placement="top" :show-after="300">
            <span class="prompt-preview">
              {{ row.system_prompt?.slice(0, 60) }}{{ row.system_prompt?.length > 60 ? '...' : '' }}
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="视觉参考图" width="170">
        <template #default="{row}">
          <div v-if="row.reference_image_urls?.length" class="reference-stack">
            <el-image
              v-for="(url, index) in row.reference_image_urls"
              :key="url"
              :src="displayUrl(url)"
              :preview-src-list="row.reference_image_urls.map(displayUrl)"
              :initial-index="index"
              fit="cover"
              class="reference-thumb table-thumb"
              preview-teleported
            />
            <span class="reference-count">{{ row.reference_image_urls.length }}/3</span>
          </div>
          <span v-else class="empty-reference">未设置</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{row}">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="duplicate(row)">复制</el-button>
          <el-button size="small" type="danger" :disabled="isProtected(row)" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="showDialog" :title="editId ? '编辑人格' : '新增人格'" width="600px">
    <el-alert
      v-if="editingProtected"
      title="正在修改全局内置人格，保存后会影响尚未创建个人版本的用户。"
      type="warning"
      :closable="false"
      show-icon
      class="edit-notice"
    />
    <el-form :model="form" label-width="100px">
      <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="描述"><el-input v-model="form.description" /></el-form-item>
      <el-form-item label="系统提示词">
        <el-input v-model="form.system_prompt" type="textarea" :rows="8"
          placeholder="你是一只猫娘..." />
      </el-form-item>
      <el-form-item label="视觉参考图">
        <div class="reference-editor">
          <div v-if="form.reference_image_urls.length" class="reference-grid">
            <div v-for="(url, index) in form.reference_image_urls" :key="url" class="reference-item">
              <el-image :src="displayUrl(url)" fit="cover" class="reference-thumb" :preview-src-list="form.reference_image_urls.map(displayUrl)" :initial-index="index" preview-teleported />
              <el-button class="remove-reference" circle size="small" type="danger" @click="removeReference(index)">×</el-button>
            </div>
          </div>
          <input ref="fileInput" class="file-input" type="file" multiple accept="image/jpeg,image/png,image/webp" @change="uploadReferences" />
          <el-button :loading="uploading" :disabled="form.reference_image_urls.length >= 3" @click="fileInput?.click()">
            上传参考图（{{ form.reference_image_urls.length }}/3）
          </el-button>
          <div class="form-hint">可选 1–3 张 JPEG、PNG 或 WebP。生成涉及该人格的图片时会按顺序作为身份与画风参考。</div>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="showDialog=false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="uploading" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'

const DEFAULT_PERSONA_SYSTEM_PROMPT = `每次回复末尾必须追加一个 JSON 注释，不要放入 Markdown 代码块，注释后不要再输出内容。
格式：<!--{"emotion":"gentle","action":null}-->。
emotion 根据当前回复从 happy/excited/curious/shy/embarrassed/caring/gentle/playful/thinking/surprised/sad/nervous/proud/sleepy/angry 中选；action 为符合人格的简短动作，无动作填 null。`

const personas = ref([]), loading = ref(false)
const showDialog = ref(false), editId = ref(null)
const editingProtected = ref(false)
const form = ref({ name: '', description: '', system_prompt: '', reference_image_urls: [] })
const saving = ref(false), uploading = ref(false), fileInput = ref(null)
const protectedIds = new Set([
  '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
  '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004',
])

async function load() {
  loading.value = true
  try {
    const res = await http.get('/admin/personas')
    personas.value = Array.isArray(res) ? res : []
  } catch (e) { personas.value = []; ElMessage.error(`人格列表加载失败：${e}`) }
  finally { loading.value = false }
}

function openAdd() {
  editId.value = null
  editingProtected.value = false
  form.value = {
    name: '',
    description: '',
    system_prompt: DEFAULT_PERSONA_SYSTEM_PROMPT,
    reference_image_urls: [],
  }
  showDialog.value = true
}
function openEdit(row) {
  editId.value = row.id
  editingProtected.value = isProtected(row)
  form.value = {
    name: row.name,
    description: row.description,
    system_prompt: row.system_prompt,
    reference_image_urls: [...(row.reference_image_urls || [])],
  }
  showDialog.value = true
}

function displayUrl(url) {
  if (!url) return ''
  return url.replace(/^http:\/\/10\.0\.2\.2:3000/i, window.location.origin)
}

function removeReference(index) {
  form.value.reference_image_urls.splice(index, 1)
}

async function uploadReferences(event) {
  const available = 3 - form.value.reference_image_urls.length
  const files = Array.from(event.target.files || []).slice(0, available)
  event.target.value = ''
  if (!files.length) return
  const allowed = new Set(['image/jpeg', 'image/png', 'image/webp'])
  const invalid = files.find(file => !allowed.has(file.type) || file.size > 10 * 1024 * 1024)
  if (invalid) {
    ElMessage.warning('参考图仅支持 JPEG、PNG、WebP，且单张不能超过 10MB')
    return
  }
  uploading.value = true
  try {
    for (const file of files) {
      const body = new FormData()
      body.append('file', file, file.name)
      const result = await http.post('/media/upload', body, { timeout: 60_000 })
      if (!result.file_url) throw new Error('服务器未返回图片地址')
      form.value.reference_image_urls.push(result.file_url)
    }
    ElMessage.success('参考图已上传')
  } catch (error) {
    ElMessage.error(`参考图上传失败：${error}`)
  } finally {
    uploading.value = false
  }
}

async function save() {
  if (!form.value.name.trim() || !form.value.system_prompt.trim()) { ElMessage.warning('名称和系统提示词不能为空'); return }
  saving.value = true
  try {
    if (editId.value) {
      await http.patch(`/admin/personas/${editId.value}`, form.value)
    } else {
      await http.post('/admin/personas', form.value)
    }
    showDialog.value = false; ElMessage.success('已保存'); load()
  } catch (e) { ElMessage.error(e) }
  finally { saving.value = false }
}

function isProtected(row) { return protectedIds.has(row.id) }

async function duplicate(row) {
  try {
    await http.post('/admin/personas', {
      name: `${row.name} 副本`,
      description: row.description || '',
      system_prompt: row.system_prompt,
      reference_image_urls: row.reference_image_urls || [],
    })
    ElMessage.success('人格副本已创建')
    await load()
  } catch (e) { ElMessage.error(`复制失败：${e}`) }
}

async function del(row) {
  try {
    await ElMessageBox.confirm(`确认删除人格「${row.name}」？`, '提示', { type: 'warning' })
    await http.delete(`/admin/personas/${row.id}`); ElMessage.success('已删除'); load()
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

.edit-notice {
  margin-bottom: 16px;
}

.subtitle {
  margin-top: 4px;
  color: var(--miao-ink-secondary);
  font-size: 12px;
}

.prompt-preview {
  color: var(--miao-ink-secondary);
  font-size: 12px;
  line-height: 1.55;
  cursor: help;
}

.reference-stack,
.reference-grid {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.reference-editor {
  width: 100%;
}

.reference-grid {
  margin-bottom: 10px;
}

.reference-item {
  position: relative;
}

.reference-thumb {
  width: 82px;
  height: 82px;
  border-radius: 10px;
  border: 1px solid var(--miao-border);
  background: var(--miao-surface-soft);
}

.table-thumb {
  width: 38px;
  height: 38px;
  margin-right: -12px;
  border: 2px solid var(--miao-surface);
}

.remove-reference {
  position: absolute;
  top: -7px;
  right: -7px;
  width: 22px;
  height: 22px;
}

.reference-count,
.empty-reference,
.form-hint {
  color: var(--miao-ink-secondary);
  font-size: 12px;
}

.form-hint {
  margin-top: 8px;
  line-height: 1.5;
}

.file-input {
  display: none;
}

@media (max-width: 640px) {
  .header-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
