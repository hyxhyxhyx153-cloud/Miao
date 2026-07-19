<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div>
          <div class="title">表情包管理</div>
          <div class="subtitle">管理对话素材、情绪标签和批量识别任务。</div>
        </div>
        <div class="header-actions">
          <el-select v-model="filterEmotion" class="emotion-filter" placeholder="按情绪筛选" clearable @change="load">
            <el-option v-for="e in emotions" :key="e" :label="e" :value="e" />
          </el-select>
          <el-button @click="openJobs">导入任务</el-button>
          <el-button @click="showBatch=true">批量导入</el-button>
          <el-button type="primary" @click="showUpload=true">+ 上传表情包</el-button>
        </div>
      </div>
    </template>

    <el-row :gutter="10" class="stat-row">
      <el-col :span="6"><div class="stat"><strong>{{ statTotals.total }}</strong><span>表情总数</span></div></el-col>
      <el-col :span="6"><div class="stat"><strong>{{ statTotals.active }}</strong><span>启用中</span></div></el-col>
      <el-col :span="6"><div class="stat"><strong>{{ statTotals.sent }}</strong><span>累计发送</span></div></el-col>
      <el-col :span="6"><div class="stat"><strong>{{ stats.length }}</strong><span>情绪分类</span></div></el-col>
    </el-row>

    <!-- Emotion tabs -->
    <el-tabs v-model="filterEmotion" class="emotion-tabs" @tab-click="load">
      <el-tab-pane label="全部" name="" />
      <el-tab-pane v-for="e in emotions" :key="e" :label="e" :name="e" />
    </el-tabs>

    <div class="emoji-grid" v-loading="loading">
      <div v-for="item in emojis" :key="item.id" class="emoji-item">
        <div class="emoji-img-wrap">
          <img :src="item.url" :alt="item.description" loading="lazy" />
          <div class="emoji-overlay">
            <el-button size="small" type="primary" circle @click="openEdit(item)"><el-icon><Edit /></el-icon></el-button>
            <el-button size="small" type="danger" circle @click="del(item)"><el-icon><Delete /></el-icon></el-button>
          </div>
        </div>
        <el-tag size="small" class="emoji-tag" :type="tagColor(item.emotionTag)">{{ item.emotionTag }}</el-tag>
        <div class="emoji-desc">{{ item.description }}</div>
        <div class="emoji-count">发送 {{ item.sendCount || 0 }} 次 · {{ item.isActive ? '启用' : '停用' }}</div>
      </div>
      <el-empty v-if="!loading && emojis.length===0" class="grid-empty" description="暂无表情包" />
    </div>
  </el-card>

  <!-- Upload dialog -->
  <el-dialog v-model="showUpload" title="上传表情包" width="500px">
    <el-form :model="uploadForm" label-width="90px">
      <el-form-item label="图片 URL">
        <el-input v-model="uploadForm.url" placeholder="https://..." />
      </el-form-item>
      <el-form-item label="情绪标签">
        <el-select v-model="uploadForm.emotionTag" class="full-width">
          <el-option v-for="e in allEmotions" :key="e.v" :label="`${e.label} (${e.v})`" :value="e.v" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="uploadForm.description" placeholder="一句话描述图片内容" />
      </el-form-item>
      <el-form-item label="文件名">
        <el-input v-model="uploadForm.filename" placeholder="happy_001.webp" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="showUpload=false">取消</el-button>
      <el-button type="primary" @click="uploadEmoji">保存</el-button>
    </template>
  </el-dialog>

  <!-- Edit dialog -->
  <el-dialog v-model="showEdit" title="编辑表情包" width="400px">
    <el-form :model="editForm" label-width="90px">
      <el-form-item label="情绪标签">
        <el-select v-model="editForm.emotionTag" class="full-width">
          <el-option v-for="e in allEmotions" :key="e.v" :label="`${e.label} (${e.v})`" :value="e.v" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="editForm.description" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="editForm.isActive" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="showEdit=false">取消</el-button>
      <el-button type="primary" @click="saveEdit">保存</el-button>
    </template>
  </el-dialog>

  <!-- Batch import dialog -->
  <el-dialog v-model="showBatch" title="批量导入表情包" width="min(1080px, calc(100vw - 32px))" :close-on-click-modal="false" :before-close="beforeBatchClose">
    <!-- Step 1: source selection -->
    <div v-if="batchStep === 1">
      <el-form label-width="100px">
        <el-form-item label="导入来源">
          <el-radio-group v-model="batchSource">
            <el-radio-button value="url">图片 URL</el-radio-button>
            <el-radio-button value="local">本地文件</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="batchSource === 'url'" label="图片 URL 列表">
          <el-input
            v-model="batchUrls"
            type="textarea"
            :rows="8"
            placeholder="每行一个 URL，最多 50 条&#10;https://example.com/emoji1.webp&#10;https://example.com/emoji2.gif"
          />
          <div class="form-hint">
            已输入 {{ urlCount }} 条{{ urlCount > 50 ? '（超出限制，将只处理前 50 条）' : '' }}
          </div>
        </el-form-item>
        <el-form-item v-else label="本地图片">
          <el-upload
            v-model:file-list="localFileList"
            drag
            multiple
            :auto-upload="false"
            :limit="50"
            accept="image/jpeg,image/png,image/webp,image/gif"
            :on-exceed="onFileExceed"
            class="batch-uploader"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖放图片到这里，或<em>点击选择</em></div>
            <template #tip><div class="el-upload__tip">最多 50 个 JPEG、PNG、WebP 或 GIF 文件；选择后才会上传。</div></template>
          </el-upload>
        </el-form-item>
        <el-form-item label="识别模型">
          <el-select v-model="batchModel" class="batch-model-select">
            <el-option value="gpt-4o" label="GPT-4o" />
            <el-option value="claude-sonnet-4-6" label="Claude Sonnet 4.6" />
          </el-select>
        </el-form-item>
      </el-form>
    </div>

    <!-- Step 2: Recognition progress -->
    <div v-if="batchStep === 2">
      <div class="batch-summary">
        <el-progress
          :percentage="batchProgress"
          class="batch-progress"
          :status="batchRecognitionDone ? 'success' : undefined"
        />
        <span>{{ processedCount }} / {{ batchItems.length }}</span>
        <el-tag v-if="batchPaused" type="warning">已暂停派发</el-tag>
        <el-tag v-else-if="activeWorkers" type="info">并发 {{ activeWorkers }} / 4</el-tag>
      </div>
      <div class="batch-counts">
        <span>待处理 {{ statusCount.pending }}</span><span>上传中 {{ statusCount.uploading }}</span><span>识别中 {{ statusCount.recognizing }}</span><span class="success-text">待保存 {{ statusCount.ready }}</span><span class="success-text">已保存 {{ statusCount.saved }}</span><span class="error-text">失败 {{ statusCount.failed + statusCount.save_failed + statusCount.unknown }}</span>
      </div>
      <el-table :data="batchItems" size="small" max-height="380" stripe>
        <el-table-column label="预览" width="70">
          <template #default="{row}">
            <img class="batch-preview" :src="row.previewUrl || row.url" :alt="row.originalFilename || '表情预览'" />
          </template>
        </el-table-column>
        <el-table-column label="文件 / URL" min-width="190">
          <template #default="{row}">
            <div class="batch-source-name">{{ row.originalFilename || row.url }}</div>
            <div v-if="row.originalFilename && row.url" class="batch-uploaded-url">{{ row.url }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{row}">
            <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="情绪标签" width="130">
          <template #default="{row}">
            <el-select
              v-if="editableStatus(row.status)"
              v-model="row.emotionTag"
              size="small"
              class="batch-emotion-select"
            >
              <el-option v-for="e in allEmotions" :key="e.v" :value="e.v" :label="`${e.label}(${e.v})`" />
            </el-select>
            <span v-else class="table-muted">{{ row.emotionTag || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="160">
          <template #default="{row}">
            <el-input
              v-if="editableStatus(row.status)"
              v-model="row.description"
              size="small"
              placeholder="描述"
            />
            <span v-else class="table-muted">{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="错误" min-width="170">
          <template #default="{row}"><span v-if="row.error" class="batch-error">{{ row.error }}</span><span v-else>-</span></template>
        </el-table-column>
        <el-table-column label="置信度" width="80">
          <template #default="{row}">
            <span v-if="row.confidence != null" class="confidence">
              {{ Math.round(row.confidence * 100) }}%
            </span>
            <span v-else class="table-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="76" fixed="right">
          <template #default="{row}"><el-button v-if="retryableStatus(row.status)" link type="primary" @click="retryItem(row)">重试</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <template #footer>
      <div class="dialog-footer-row">
        <div>
          <el-button v-if="batchStep===2 && batchRecognitionDone && activeWorkers===0" :disabled="batchSaving" @click="resetBatch">重新开始</el-button>
        </div>
        <div class="dialog-footer-actions">
          <el-button @click="requestBatchClose">关闭</el-button>
          <el-button
            v-if="batchStep===1"
            type="primary"
            :disabled="!batchCanStart"
            @click="startRecognition"
          >开始识别</el-button>
          <el-button v-if="batchStep===2 && !batchRecognitionDone && !batchPaused" type="warning" @click="pauseBatch">暂停</el-button>
          <el-button v-if="batchStep===2 && !batchRecognitionDone && batchPaused" type="primary" @click="resumeBatch">继续</el-button>
          <el-button
            v-if="batchStep===2 && batchRecognitionDone && statusCount.ready > 0"
            type="primary"
            :loading="batchSaving"
            @click="saveAll"
          >保存 {{ statusCount.ready }} 条</el-button>
        </div>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="jobsVisible" title="表情导入任务历史" width="min(900px, calc(100vw - 32px))">
    <el-table :data="jobs" stripe max-height="500" v-loading="jobsLoading">
      <el-table-column prop="id" label="任务 ID" min-width="200"><template #default="{ row }"><span class="job-id">{{ row.id }}</span></template></el-table-column>
      <el-table-column label="创建时间" width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
      <el-table-column prop="total_count" label="总数" width="70" />
      <el-table-column prop="success_count" label="成功" width="70" />
      <el-table-column prop="failed_count" label="失败" width="70" />
      <el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="jobType(row.status)">{{ jobLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="结果" width="90"><template #default="{ row }"><el-button link type="primary" @click="selectedJob=row">查看</el-button></template></el-table-column>
    </el-table>
    <el-alert v-if="selectedJob" class="job-alert" :closable="true" type="info" @close="selectedJob=null"><template #title>任务 {{ selectedJob.id }} 明细</template><pre class="job-detail">{{ prettyJob(selectedJob.items) }}</pre></el-alert>
    <template #footer><el-button @click="jobsVisible=false">关闭</el-button><el-button :loading="jobsLoading" @click="loadJobs">刷新</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'

const emojis = ref([]), loading = ref(false)
const emotions = ref([]), filterEmotion = ref('')
const stats = ref([])
const jobs = ref([]), jobsVisible = ref(false), jobsLoading = ref(false), selectedJob = ref(null)
const showUpload = ref(false), showEdit = ref(false)
const uploadForm = ref({ url: '', emotionTag: 'happy', description: '', filename: '' })
const editForm = ref({ id: '', emotionTag: '', description: '', isActive: true })

// Batch import state
const showBatch = ref(false)
const batchStep = ref(1)
const batchSource = ref('url')
const batchUrls = ref('')
const localFileList = ref([])
const batchModel = ref('claude-sonnet-4-6')
const batchItems = ref([])
const batchSaving = ref(false)
const batchPaused = ref(false)
const activeWorkers = ref(0)
const MAX_CONCURRENCY = 4
let queueGeneration = 0

const urlCount = computed(() => {
  return batchUrls.value.split('\n').map(l => l.trim()).filter(Boolean).length
})

const batchProgress = computed(() => {
  if (!batchItems.value.length) return 0
  return Math.round((processedCount.value / batchItems.value.length) * 100)
})

const statusCount = computed(() => {
  const counts = { pending: 0, uploading: 0, recognizing: 0, ready: 0, saving: 0, saved: 0, failed: 0, save_failed: 0, unknown: 0 }
  for (const item of batchItems.value) counts[item.status] = (counts[item.status] || 0) + 1
  return counts
})
const processedCount = computed(() => batchItems.value.filter(item => !['pending', 'uploading', 'recognizing'].includes(item.status)).length)
const batchRecognitionDone = computed(() => {
  return batchItems.value.length > 0 &&
    batchItems.value.every(item => !['pending', 'uploading', 'recognizing'].includes(item.status))
})
const batchCanStart = computed(() => batchSource.value === 'url' ? urlCount.value > 0 : localFileList.value.length > 0)
const statTotals = computed(() => stats.value.reduce((totals, row) => ({
  total: totals.total + Number(row.count || 0), active: totals.active + Number(row.active_count || 0), sent: totals.sent + Number(row.send_count || 0),
}), { total: 0, active: 0, sent: 0 }))

const allEmotions = [
  { v: 'happy', label: '开心' }, { v: 'excited', label: '兴奋' }, { v: 'curious', label: '好奇' },
  { v: 'shy', label: '害羞' }, { v: 'embarrassed', label: '不好意思' }, { v: 'caring', label: '关心' },
  { v: 'gentle', label: '温柔' }, { v: 'playful', label: '调皮' }, { v: 'thinking', label: '思考' },
  { v: 'surprised', label: '惊讶' }, { v: 'sad', label: '难过' }, { v: 'nervous', label: '紧张' },
  { v: 'proud', label: '自豪' }, { v: 'sleepy', label: '困倦' }, { v: 'angry', label: '生气' },
]

function tagColor(v) {
  const m = { happy: 'success', sad: 'danger', angry: 'danger', shy: 'warning', excited: '' }
  return m[v] || 'info'
}

async function load() {
  loading.value = true
  try {
    const params = {}
    if (filterEmotion.value) params.emotion_tag = filterEmotion.value
    emojis.value = await http.get('/admin/emojis', { params })
  } catch (e) { ElMessage.error(e) }
  finally { loading.value = false }
}

async function loadEmotions() {
  try { emotions.value = await http.get('/emojis/emotions') }
  catch (error) { ElMessage.error(`情绪标签加载失败：${error}`) }
}

async function loadStats() {
  try { stats.value = await http.get('/admin/emojis/stats') }
  catch (error) { ElMessage.error(`表情统计加载失败：${error}`) }
}

async function loadJobs() {
  jobsLoading.value = true
  try { jobs.value = await http.get('/admin/emojis/import-jobs') }
  catch (error) { ElMessage.error(`导入任务加载失败：${error}`) }
  finally { jobsLoading.value = false }
}
function openJobs() { jobsVisible.value = true; selectedJob.value = null; loadJobs() }

async function uploadEmoji() {
  if (!uploadForm.value.url.trim() || !uploadForm.value.filename.trim() || !uploadForm.value.emotionTag) {
    ElMessage.warning('图片 URL、文件名和情绪标签不能为空')
    return
  }
  try {
    await http.post('/admin/emojis', { ...uploadForm.value, sceneKeywords: [] })
    showUpload.value = false
    ElMessage.success('上传成功')
    await Promise.all([load(), loadEmotions(), loadStats()])
  } catch (e) { ElMessage.error(e) }
}

function openEdit(item) {
  editForm.value = { id: item.id, emotionTag: item.emotionTag, description: item.description, isActive: item.isActive }
  showEdit.value = true
}

async function saveEdit() {
  try {
    await http.patch(`/admin/emojis/${editForm.value.id}`, editForm.value)
    showEdit.value = false
    ElMessage.success('已更新')
    await Promise.all([load(), loadStats()])
  } catch (e) { ElMessage.error(e) }
}

async function del(item) {
  try {
    await ElMessageBox.confirm('确认删除这个表情包？', '提示', { type: 'warning' })
    await http.delete(`/admin/emojis/${item.id}`); ElMessage.success('已删除'); await Promise.all([load(), loadStats()])
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e) }
}

function resetBatch() {
  queueGeneration += 1
  for (const item of batchItems.value) {
    if (item.previewUrl?.startsWith('blob:')) URL.revokeObjectURL(item.previewUrl)
  }
  batchStep.value = 1
  batchItems.value = []
  batchUrls.value = ''
  localFileList.value = []
  batchPaused.value = false
}

function onFileExceed() { ElMessage.warning('一次最多选择 50 个文件') }

function startRecognition() {
  const base = { status: 'pending', emotionTag: '', description: '', confidence: null, error: '' }
  if (batchSource.value === 'url') {
    const urls = batchUrls.value.split('\n').map(line => line.trim()).filter(Boolean).slice(0, 50)
    batchItems.value = urls.map((url, index) => ({ ...base, id: `url-${index}-${url}`, url, filename: filenameFromUrl(url) }))
  } else {
    const supported = /^image\/(jpeg|png|webp|gif)$/
    const files = localFileList.value.map(entry => entry.raw).filter(file => file && supported.test(file.type)).slice(0, 50)
    const rejected = localFileList.value.length - files.length
    if (rejected) ElMessage.warning(`已忽略 ${rejected} 个不支持的文件`)
    batchItems.value = files.map((file, index) => ({
      ...base,
      id: `file-${index}-${file.name}-${file.lastModified}`,
      file,
      originalFilename: file.name,
      filename: file.name,
      previewUrl: URL.createObjectURL(file),
      url: '',
    }))
  }
  if (!batchItems.value.length) return
  batchStep.value = 2
  batchPaused.value = false
  queueGeneration += 1
  pumpQueue(queueGeneration)
}

function pumpQueue(generation) {
  if (generation !== queueGeneration || batchPaused.value) return
  while (activeWorkers.value < MAX_CONCURRENCY) {
    const item = batchItems.value.find(candidate => candidate.status === 'pending')
    if (!item) return
    activeWorkers.value += 1
    processItem(item).finally(() => {
      activeWorkers.value -= 1
      pumpQueue(generation)
    })
  }
}

async function processItem(item) {
  try {
    item.error = ''
    if (item.file && !item.url) {
      item.status = 'uploading'
      const form = new FormData()
      form.append('file', item.file, item.originalFilename)
      const uploaded = await http.post('/media/upload', form)
      if (!uploaded?.file_url) throw new Error('上传接口未返回文件 URL')
      item.url = uploaded.file_url
      item.filename = uploaded.filename || item.originalFilename
    }
    item.status = 'recognizing'
    const result = await http.post('/admin/emojis/batch-recognize', { url: item.url, model: batchModel.value })
    if (!result?.emotionTag) throw new Error('识别接口未返回情绪标签')
    item.emotionTag = result.emotionTag
    item.description = result.description || ''
    item.confidence = result.confidence ?? null
    item.status = 'ready'
  } catch (error) {
    item.failedStage = item.status
    item.error = String(error)
    item.status = 'failed'
  }
}

function pauseBatch() { batchPaused.value = true }
function resumeBatch() { batchPaused.value = false; pumpQueue(queueGeneration) }

async function retryItem(item) {
  if (item.status === 'failed') {
    item.error = ''
    item.status = 'pending'
    if (!batchPaused.value) pumpQueue(queueGeneration)
    else ElMessage.info('条目已回到队列，点击“继续”后处理')
    return
  }
  if (item.status === 'unknown') {
    try {
      await ElMessageBox.confirm('服务端上次结果未知，重试可能造成重复数据。请先在“导入任务”中核对，仍要重试吗？', '谨慎重试', { type: 'warning' })
    } catch { return }
  }
  await saveItems([item])
}

async function saveAll() {
  const toSave = batchItems.value.filter(item => item.status === 'ready')
  if (!toSave.length) { ElMessage.warning('没有可保存的条目'); return }
  await saveItems(toSave)
}

async function saveItems(toSave) {
  batchSaving.value = true
  toSave.forEach(item => { item.status = 'saving'; item.error = '' })
  try {
    const job = await http.post('/admin/emojis/import-jobs', {
      items: toSave.map(item => ({
        url: item.url,
        emotionTag: item.emotionTag,
        description: item.description,
        sceneKeywords: [],
        filename: item.filename || filenameFromUrl(item.url),
      })),
    })
    const results = parseJobItems(job.items)
    const buckets = new Map()
    for (const result of results) {
      const bucket = buckets.get(result.filename) || []
      bucket.push(result)
      buckets.set(result.filename, bucket)
    }
    for (const item of toSave) {
      const filename = item.filename || filenameFromUrl(item.url)
      const result = buckets.get(filename)?.shift()
      if (result?.status === 'success') item.status = 'saved'
      else if (result) { item.status = 'save_failed'; item.error = result.error || '服务端保存失败' }
      else { item.status = 'unknown'; item.error = '导入任务未返回该条目的结果，请在任务历史中核对' }
    }
    const saved = toSave.filter(item => item.status === 'saved').length
    const failed = toSave.length - saved
    if (failed) ElMessage.warning(`服务端任务已返回：成功 ${saved} 条，失败或未知 ${failed} 条`)
    else ElMessage.success(`服务端确认已保存 ${saved} 条`)
    if (saved) await Promise.all([load(), loadEmotions(), loadStats()])
    if (jobsVisible.value) await loadJobs()
  } catch (error) {
    toSave.forEach(item => { item.status = 'unknown'; item.error = `请求结果未知：${error}` })
    ElMessage.error('未能确认导入任务结果，请先在任务历史中核对后再重试')
  } finally {
    batchSaving.value = false
  }
}

function parseJobItems(value) {
  if (Array.isArray(value)) return value
  if (typeof value === 'string') { try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed : [] } catch { return [] } }
  return []
}
function filenameFromUrl(url) { try { return decodeURIComponent(new URL(url).pathname.split('/').pop()) || 'emoji.webp' } catch { return 'emoji.webp' } }
function editableStatus(status) { return status === 'ready' }
function retryableStatus(status) { return ['failed', 'save_failed', 'unknown'].includes(status) }
function statusType(status) { return { pending:'info', uploading:'warning', recognizing:'warning', ready:'success', saving:'warning', saved:'success', failed:'danger', save_failed:'danger', unknown:'warning' }[status] || 'info' }
function statusLabel(status) { return { pending:'待处理', uploading:'上传中', recognizing:'识别中', ready:'待保存', saving:'保存中', saved:'已保存', failed:'处理失败', save_failed:'保存失败', unknown:'结果未知' }[status] || status }

async function beforeBatchClose(done) {
  const unfinished = activeWorkers.value > 0 || batchItems.value.some(item => item.status === 'pending') || batchSaving.value
  if (!unfinished) { done(); return }
  const wasPaused = batchPaused.value
  pauseBatch()
  try {
    await ElMessageBox.confirm('关闭窗口会暂停派发新任务；已经发出的上传或识别请求仍会完成。再次打开可继续。', '任务仍在进行', { type: 'warning' })
    done()
  } catch { if (!wasPaused && !batchRecognitionDone.value) resumeBatch() }
}
function requestBatchClose() { beforeBatchClose(() => { showBatch.value = false }) }

function jobType(status) { return status === 'completed' ? 'success' : status === 'completed_with_errors' ? 'warning' : status === 'running' ? '' : 'info' }
function jobLabel(status) { return { completed: '已完成', completed_with_errors: '部分失败', running: '进行中', cancelled: '已取消' }[status] || status }
function prettyJob(value) { if (typeof value === 'string') { try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value } } return JSON.stringify(value || [], null, 2) }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }

onMounted(() => { load(); loadEmotions(); loadStats() })
onUnmounted(() => {
  batchPaused.value = true
  queueGeneration += 1
  for (const item of batchItems.value) {
    if (item.previewUrl?.startsWith('blob:')) URL.revokeObjectURL(item.previewUrl)
  }
})
</script>

<style scoped>
.header-row,
.header-actions,
.dialog-footer-row,
.dialog-footer-actions {
  display: flex;
  align-items: center;
}

.header-row,
.dialog-footer-row {
  justify-content: space-between;
  gap: 16px;
}

.header-actions,
.dialog-footer-actions {
  gap: 8px;
}

.title {
  font-weight: 650;
}

.subtitle,
.form-hint,
.table-muted {
  color: var(--miao-ink-secondary);
  font-size: 12px;
}

.subtitle,
.form-hint {
  margin-top: 4px;
}

.emotion-filter {
  width: 140px;
}

.stat-row {
  margin-bottom: 14px;
}

.emotion-tabs {
  margin-bottom: 8px;
}

.grid-empty {
  grid-column: 1 / -1;
}

.full-width {
  width: 100%;
}

.batch-model-select {
  width: 220px;
}

.emoji-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 14px;
  min-height: 200px;
}

.emoji-item {
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.84);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 5px 16px rgba(50, 54, 76, 0.055);
  transition: transform 220ms var(--miao-ease), box-shadow 220ms ease;
}

.emoji-item:hover {
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.92), 0 11px 26px rgba(50, 54, 76, 0.1);
  transform: translateY(-2px);
}

.emoji-img-wrap {
  position: relative;
  overflow: hidden;
  aspect-ratio: 1;
  background: rgba(241, 242, 246, 0.82);
}

.emoji-img-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 300ms var(--miao-ease);
}

.emoji-item:hover .emoji-img-wrap img {
  transform: scale(1.035);
}

.emoji-overlay {
  position: absolute; inset: 0; background: rgba(35,38,50,.4);
  display: flex; align-items: center; justify-content: center; gap: 6px;
  opacity: 0; transition: opacity 180ms ease;
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.emoji-img-wrap:hover .emoji-overlay,
.emoji-img-wrap:focus-within .emoji-overlay {
  opacity: 1;
}

.emoji-tag {
  display: inline-flex;
  margin: 8px 8px 3px;
}

.emoji-desc {
  overflow: hidden;
  padding: 2px 9px 5px;
  color: var(--miao-ink-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.emoji-count {
  padding: 0 9px 10px;
  color: var(--miao-ink-tertiary);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.stat {
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 13px;
  border: 1px solid rgba(97, 116, 216, 0.08);
  border-radius: 12px;
  background: rgba(237, 240, 255, 0.54);
}

.stat strong {
  color: var(--miao-accent-deep);
  font-size: 21px;
  font-variant-numeric: tabular-nums;
}

.stat span {
  color: var(--miao-ink-secondary);
  font-size: 11px;
}

.batch-uploader {
  width: 100%;
}

.batch-progress {
  flex: 1;
}

.batch-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  color: var(--miao-ink-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.batch-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 12px;
  color: var(--miao-ink-secondary);
  font-size: 12px;
}

.batch-preview {
  width: 50px;
  height: 50px;
  border-radius: 9px;
  background: rgba(241, 242, 246, 0.82);
  object-fit: cover;
}

.batch-emotion-select {
  width: 120px;
}

.confidence {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.success-text {
  color: #4f9074;
}

.error-text,
.batch-error {
  color: #b64e57;
}

.batch-error {
  font-size: 11px;
}

.batch-source-name {
  font-size: 12px;
  word-break: break-all;
}

.batch-uploaded-url {
  margin-top: 3px;
  color: var(--miao-ink-tertiary);
  font-size: 10px;
  word-break: break-all;
}

.job-id {
  font: 11px ui-monospace, SFMono-Regular, Consolas, monospace;
}

.job-alert {
  margin-top: 12px;
}

.job-detail {
  max-height: 240px;
  margin-bottom: 0;
  overflow: auto;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 860px) {
  .header-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .header-actions {
    width: 100%;
    flex-wrap: wrap;
  }
}

@media (max-width: 560px) {
  .stat-row :deep(.el-col) {
    width: 50%;
    max-width: 50%;
    flex: 0 0 50%;
    margin-bottom: 8px;
  }

  .dialog-footer-row {
    align-items: stretch;
    flex-direction: column;
  }

  .dialog-footer-actions {
    flex-wrap: wrap;
  }
}
</style>
