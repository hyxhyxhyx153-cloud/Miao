<template>
  <div class="release-page">
    <el-alert
      title="版本更新与公告会在用户启动应用或登录后检查；客户端检测到强制更新后，将阻止继续使用。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-row :gutter="16" class="top-grid">
      <el-col :xs="24" :xl="14">
        <el-card shadow="never" v-loading="releaseLoading">
          <template #header>
            <div class="header-row">
              <div>
                <div class="title">Android APK 发布</div>
                <div class="subtitle">上传安装包并配置客户端升级策略。</div>
              </div>
              <el-button type="primary" :loading="releaseSaving" @click="saveRelease">发布版本</el-button>
            </div>
          </template>

          <el-form :model="releaseForm" label-position="top">
            <el-row :gutter="12">
              <el-col :xs="24" :sm="8">
                <el-form-item label="版本名称">
                  <el-input v-model="releaseForm.latestVersion" placeholder="例如 1.2.0" maxlength="32" />
                </el-form-item>
              </el-col>
              <el-col :xs="12" :sm="8">
                <el-form-item label="最新版本码">
                  <el-input-number v-model="releaseForm.latestVersionCode" :min="1" :max="2147483647" controls-position="right" />
                </el-form-item>
              </el-col>
              <el-col :xs="12" :sm="8">
                <el-form-item label="最低支持版本码">
                  <el-input-number v-model="releaseForm.minSupportedVersionCode" :min="1" :max="2147483647" controls-position="right" />
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item label="APK 文件">
              <div class="upload-line">
                <input ref="apkInput" class="file-input" type="file" accept=".apk,application/vnd.android.package-archive" @change="uploadApk" />
                <el-button :loading="uploading" @click="apkInput?.click()">选择并上传 APK</el-button>
                <el-progress v-if="uploading" :percentage="uploadProgress" :stroke-width="8" class="upload-progress" />
                <span v-else class="hint">最大 200MB，服务端会校验 APK 归档与 AndroidManifest。</span>
              </div>
            </el-form-item>

            <el-form-item label="下载地址">
              <el-input v-model="releaseForm.downloadUrl" placeholder="上传后自动填写，也可使用可信的 HTTPS 地址" />
            </el-form-item>

            <el-form-item label="更新说明">
              <el-input v-model="releaseForm.releaseNotes" type="textarea" :rows="6" maxlength="4000" show-word-limit placeholder="逐行填写本次版本的主要变化" />
            </el-form-item>

            <el-form-item label="强制更新">
              <el-switch v-model="releaseForm.forceUpdate" />
              <span class="switch-help">开启后所有低于最新版本码的客户端都必须升级；低于最低支持版本码时始终强制升级。</span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :xl="10">
        <el-card shadow="never" v-loading="legalLoading">
          <template #header>
            <div class="header-row">
              <div>
                <div class="title">法律文档</div>
                <div class="subtitle">登录与注册页向用户展示的内容。</div>
              </div>
              <el-button type="primary" :loading="legalSaving" @click="saveLegal">保存文档</el-button>
            </div>
          </template>
          <el-form :model="legalForm" label-position="top">
            <el-form-item label="文档版本">
              <el-input v-model="legalForm.version" placeholder="例如 2026.07" maxlength="64" />
            </el-form-item>
            <el-form-item label="隐私政策">
              <el-input v-model="legalForm.privacyPolicy" type="textarea" :rows="16" :maxlength="LEGAL_DOCUMENT_MAX_LENGTH" show-word-limit />
            </el-form-item>
            <el-form-item label="用户协议">
              <el-input v-model="legalForm.userAgreement" type="textarea" :rows="16" :maxlength="LEGAL_DOCUMENT_MAX_LENGTH" show-word-limit />
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="announcement-card" v-loading="announcementLoading">
      <template #header>
        <div class="header-row">
          <div>
            <div class="title">公告管理</div>
            <div class="subtitle">公告在设定时间内生效，并在每台设备上按公告 ID 展示一次。</div>
          </div>
          <el-button type="primary" @click="openAnnouncement()">新增公告</el-button>
        </div>
      </template>

      <el-table :data="announcements" stripe class="announcement-table">
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="announcementStatus(row).type">{{ announcementStatus(row).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="170" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }"><el-tag :type="typeTag(row.type)">{{ typeLabel(row.type) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="置顶" width="80">
          <template #default="{ row }">{{ boolValue(row, 'isPinned', 'is_pinned') ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="生效时间" min-width="300">
          <template #default="{ row }">
            {{ formatTime(valueOf(row, 'startsAt', 'starts_at')) }} — {{ formatTime(valueOf(row, 'endsAt', 'ends_at')) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="255" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openAnnouncement(row)">编辑</el-button>
            <el-button size="small" @click="toggleAnnouncement(row)">{{ boolValue(row, 'isActive', 'is_active') ? '停用' : '启用' }}</el-button>
            <el-button size="small" type="danger" @click="removeAnnouncement(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="announcements.length" class="announcement-mobile-list">
        <article v-for="row in announcements" :key="row.id" class="announcement-mobile-item">
          <div class="announcement-mobile-heading">
            <div>
              <strong>{{ row.title }}</strong>
              <div class="announcement-mobile-tags">
                <el-tag size="small" :type="announcementStatus(row).type">{{ announcementStatus(row).label }}</el-tag>
                <el-tag size="small" :type="typeTag(row.type)">{{ typeLabel(row.type) }}</el-tag>
                <el-tag v-if="boolValue(row, 'isPinned', 'is_pinned')" size="small">置顶</el-tag>
              </div>
            </div>
          </div>
          <div class="announcement-mobile-time">
            {{ formatTime(valueOf(row, 'startsAt', 'starts_at')) }} — {{ formatTime(valueOf(row, 'endsAt', 'ends_at')) }}
          </div>
          <div class="announcement-mobile-actions">
            <el-button size="small" @click="openAnnouncement(row)">编辑</el-button>
            <el-button size="small" @click="toggleAnnouncement(row)">{{ boolValue(row, 'isActive', 'is_active') ? '停用' : '启用' }}</el-button>
            <el-button size="small" type="danger" @click="removeAnnouncement(row)">删除</el-button>
          </div>
        </article>
      </div>
      <el-empty v-if="!announcementLoading && !announcements.length" description="暂无公告" />
    </el-card>

    <el-dialog v-model="announcementDialog" :title="announcementForm.id ? '编辑公告' : '新增公告'" width="min(680px, 94vw)">
      <el-form :model="announcementForm" label-position="top">
        <el-form-item label="标题"><el-input v-model="announcementForm.title" maxlength="120" show-word-limit /></el-form-item>
        <el-form-item label="正文"><el-input v-model="announcementForm.content" type="textarea" :rows="9" maxlength="10000" show-word-limit /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="类型">
              <el-select v-model="announcementForm.type" style="width:100%">
                <el-option label="普通" value="info" />
                <el-option label="重要" value="warning" />
                <el-option label="维护" value="maintenance" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="启用"><el-switch v-model="announcementForm.isActive" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="置顶"><el-switch v-model="announcementForm.isPinned" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12">
            <el-form-item label="开始时间"><el-date-picker v-model="announcementForm.startsAt" type="datetime" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="结束时间（可选）"><el-date-picker v-model="announcementForm.endsAt" type="datetime" clearable style="width:100%" /></el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="announcementDialog=false">取消</el-button>
        <el-button type="primary" :loading="announcementSaving" @click="saveAnnouncement">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'

// Must match server/src/services/appContent.js.
const LEGAL_DOCUMENT_MAX_LENGTH = 200_000

const releaseLoading = ref(false)
const releaseSaving = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const apkInput = ref(null)
const releaseForm = ref({
  latestVersion: '1.0',
  latestVersionCode: 1,
  minSupportedVersionCode: 1,
  forceUpdate: false,
  downloadUrl: '',
  releaseNotes: '',
})

const legalLoading = ref(false)
const legalSaving = ref(false)
const legalForm = ref({ version: '1.0', privacyPolicy: '', userAgreement: '' })

const announcementLoading = ref(false)
const announcementSaving = ref(false)
const announcementDialog = ref(false)
const announcements = ref([])
const emptyAnnouncement = () => ({
  id: null,
  title: '',
  content: '',
  type: 'info',
  isActive: true,
  isPinned: false,
  startsAt: new Date(),
  endsAt: null,
})
const announcementForm = ref(emptyAnnouncement())

function valueOf(row, camel, snake) { return row?.[camel] ?? row?.[snake] ?? null }
function boolValue(row, camel, snake) { return Boolean(valueOf(row, camel, snake)) }
function normalizeRelease(value = {}) {
  return {
    latestVersion: String(value.latestVersion ?? value.latest_android_version ?? '1.0'),
    latestVersionCode: Number(value.latestVersionCode ?? value.latest_android_version_code ?? 1),
    minSupportedVersionCode: Number(value.minSupportedVersionCode ?? value.android_min_supported_version_code ?? 1),
    forceUpdate: Boolean(value.forceUpdate ?? value.android_force_update ?? false),
    downloadUrl: String(value.downloadUrl ?? value.android_download_url ?? ''),
    releaseNotes: String(value.releaseNotes ?? value.android_release_notes ?? ''),
  }
}

async function loadRelease() {
  releaseLoading.value = true
  try { releaseForm.value = normalizeRelease(await http.get('/admin/app-release')) }
  catch (error) { ElMessage.error(`版本配置加载失败：${error}`) }
  finally { releaseLoading.value = false }
}

async function saveRelease() {
  const form = releaseForm.value
  if (!form.latestVersion.trim()) return ElMessage.warning('版本名称不能为空')
  if (!Number.isInteger(form.latestVersionCode) || !Number.isInteger(form.minSupportedVersionCode)) return ElMessage.warning('版本码必须是整数')
  if (form.minSupportedVersionCode > form.latestVersionCode) return ElMessage.warning('最低支持版本码不能高于最新版本码')
  if ((form.forceUpdate || form.minSupportedVersionCode > 1) && !form.downloadUrl.trim()) {
    return ElMessage.warning('强制更新或提高最低支持版本时，必须提供 APK 下载地址')
  }
  releaseSaving.value = true
  try {
    releaseForm.value = normalizeRelease(await http.put('/admin/app-release', form))
    ElMessage.success('版本发布配置已保存')
  } catch (error) { ElMessage.error(`发布失败：${error}`) }
  finally { releaseSaving.value = false }
}

async function uploadApk(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.apk')) return ElMessage.warning('请选择 .apk 文件')
  if (file.size > 200 * 1024 * 1024) return ElMessage.warning('APK 不能超过 200MB')
  const body = new FormData()
  body.append('file', file, file.name)
  uploading.value = true
  uploadProgress.value = 0
  try {
    const result = await http.post('/admin/app-release/upload', body, {
      timeout: 10 * 60_000,
      onUploadProgress: progress => {
        if (progress.total) uploadProgress.value = Math.min(100, Math.round(progress.loaded / progress.total * 100))
      },
    })
    releaseForm.value.downloadUrl = result.downloadUrl || result.fileUrl || result.url || ''
    uploadProgress.value = 100
    ElMessage.success('APK 上传成功，请确认版本信息后点击发布版本')
  } catch (error) { ElMessage.error(`APK 上传失败：${error}`) }
  finally { uploading.value = false }
}

async function loadLegal() {
  legalLoading.value = true
  try {
    const value = await http.get('/admin/legal')
    legalForm.value = {
      version: String(value.version ?? value.legalVersion ?? '1.0'),
      privacyPolicy: String(value.privacyPolicy ?? value.privacy_policy_content ?? ''),
      userAgreement: String(value.userAgreement ?? value.user_agreement_content ?? ''),
    }
  } catch (error) { ElMessage.error(`法律文档加载失败：${error}`) }
  finally { legalLoading.value = false }
}

async function saveLegal() {
  if (!legalForm.value.version.trim() || !legalForm.value.privacyPolicy.trim() || !legalForm.value.userAgreement.trim()) {
    return ElMessage.warning('文档版本、隐私政策和用户协议均不能为空')
  }
  legalSaving.value = true
  try {
    await http.put('/admin/legal', legalForm.value)
    ElMessage.success('法律文档已保存')
  } catch (error) { ElMessage.error(`保存失败：${error}`) }
  finally { legalSaving.value = false }
}

function normalizeAnnouncement(row) {
  return {
    ...row,
    isActive: boolValue(row, 'isActive', 'is_active'),
    isPinned: boolValue(row, 'isPinned', 'is_pinned'),
    startsAt: valueOf(row, 'startsAt', 'starts_at'),
    endsAt: valueOf(row, 'endsAt', 'ends_at'),
  }
}

async function loadAnnouncements() {
  announcementLoading.value = true
  try {
    const result = await http.get('/admin/announcements')
    announcements.value = (Array.isArray(result) ? result : result.items || []).map(normalizeAnnouncement)
  } catch (error) { ElMessage.error(`公告加载失败：${error}`) }
  finally { announcementLoading.value = false }
}

function openAnnouncement(row = null) {
  announcementForm.value = row ? {
    id: row.id,
    title: row.title || '',
    content: row.content || '',
    type: row.type || 'info',
    isActive: boolValue(row, 'isActive', 'is_active'),
    isPinned: boolValue(row, 'isPinned', 'is_pinned'),
    startsAt: valueOf(row, 'startsAt', 'starts_at') ? new Date(valueOf(row, 'startsAt', 'starts_at')) : new Date(),
    endsAt: valueOf(row, 'endsAt', 'ends_at') ? new Date(valueOf(row, 'endsAt', 'ends_at')) : null,
  } : emptyAnnouncement()
  announcementDialog.value = true
}

async function saveAnnouncement() {
  const form = announcementForm.value
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning('公告标题和正文不能为空')
  if (!form.startsAt || (form.endsAt && form.endsAt <= form.startsAt)) return ElMessage.warning('结束时间必须晚于开始时间')
  const payload = {
    title: form.title.trim(),
    content: form.content.trim(),
    type: form.type,
    isActive: form.isActive,
    isPinned: form.isPinned,
    startsAt: new Date(form.startsAt).toISOString(),
    endsAt: form.endsAt ? new Date(form.endsAt).toISOString() : null,
  }
  announcementSaving.value = true
  try {
    if (form.id) await http.patch(`/admin/announcements/${form.id}`, payload)
    else await http.post('/admin/announcements', payload)
    announcementDialog.value = false
    ElMessage.success('公告已保存')
    await loadAnnouncements()
  } catch (error) { ElMessage.error(`公告保存失败：${error}`) }
  finally { announcementSaving.value = false }
}

async function toggleAnnouncement(row) {
  try {
    await http.patch(`/admin/announcements/${row.id}`, { isActive: !boolValue(row, 'isActive', 'is_active') })
    await loadAnnouncements()
  } catch (error) { ElMessage.error(`状态更新失败：${error}`) }
}

async function removeAnnouncement(row) {
  try {
    await ElMessageBox.confirm(`确认删除公告「${row.title}」？`, '删除公告', { type: 'warning' })
    await http.delete(`/admin/announcements/${row.id}`)
    ElMessage.success('公告已删除')
    await loadAnnouncements()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(`删除失败：${error}`)
  }
}

function typeLabel(type) { return ({ info: '普通', warning: '重要', maintenance: '维护' })[type] || type }
function typeTag(type) { return ({ info: 'info', warning: 'warning', maintenance: 'danger' })[type] || 'info' }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '长期' }
function announcementStatus(row) {
  if (!boolValue(row, 'isActive', 'is_active')) return { label: '已停用', type: 'info' }
  const now = Date.now()
  const start = new Date(valueOf(row, 'startsAt', 'starts_at')).getTime()
  const endValue = valueOf(row, 'endsAt', 'ends_at')
  const end = endValue ? new Date(endValue).getTime() : null
  if (start > now) return { label: '待生效', type: 'warning' }
  if (end && end <= now) return { label: '已结束', type: 'info' }
  return { label: '推送中', type: 'success' }
}

onMounted(() => Promise.all([loadRelease(), loadLegal(), loadAnnouncements()]))
</script>

<style scoped>
.release-page { display:flex; flex-direction:column; gap:16px; }
.top-grid { margin-top:0; }
.top-grid > .el-col + .el-col { margin-top:0; }
.header-row { display:flex; align-items:center; justify-content:space-between; gap:16px; }
.title { color:var(--miao-ink); font-weight:680; }
.subtitle, .hint, .switch-help { color:var(--miao-ink-secondary); font-size:12px; line-height:1.6; }
.subtitle { margin-top:3px; }
.upload-line { width:100%; display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.file-input { display:none; }
.upload-progress { width:min(320px, 100%); }
.switch-help { margin-left:12px; }
.announcement-card { margin-top:0; }
.announcement-mobile-list { display:none; }
@media (max-width:1199px) { .top-grid > .el-col + .el-col { margin-top:16px; } }
@media (max-width:640px) {
  .header-row { align-items:flex-start; flex-direction:column; }
  .header-row .el-button { width:100%; }
  .announcement-table { display:none; }
  .announcement-mobile-list { display:flex; flex-direction:column; gap:10px; }
  .announcement-mobile-item { padding:14px; border:1px solid var(--miao-border); border-radius:14px; background:var(--miao-surface-soft); }
  .announcement-mobile-heading strong { display:block; color:var(--miao-ink); line-height:1.45; }
  .announcement-mobile-tags { display:flex; flex-wrap:wrap; gap:6px; margin-top:9px; }
  .announcement-mobile-time { margin-top:10px; color:var(--miao-ink-secondary); font-size:12px; line-height:1.6; }
  .announcement-mobile-actions { display:grid; grid-template-columns:repeat(3, 1fr); gap:8px; margin-top:12px; }
  .announcement-mobile-actions .el-button { width:100%; margin:0; }
}
</style>
