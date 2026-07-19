<template>
  <el-row :gutter="16">
    <el-col :xs="24" :lg="14">
      <el-card shadow="never" v-loading="loading">
        <template #header>
          <div class="header-row">
            <div>
              <div class="title">全局配额设置</div>
              <div class="subtitle">修改后立即应用于服务端；重置时间固定为 UTC 00:00。</div>
            </div>
            <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
          </div>
        </template>
        <el-form :model="form" label-position="top">
          <el-form-item label="新用户默认每日配额">
            <el-input-number v-model="form.defaultDailyQuota" :min="0" :max="100000" controls-position="right" />
            <span class="hint">条消息 / 天</span>
          </el-form-item>
          <el-form-item label="配额重置时间">
            <el-input model-value="UTC 00:00" disabled />
          </el-form-item>
          <el-form-item label="配额耗尽提示文案">
            <el-input v-model="form.quotaExhaustedMessage" type="textarea" :rows="4" maxlength="300" show-word-limit />
          </el-form-item>
          <el-divider />
          <el-form-item label="管理端 IP 白名单（可选）">
            <el-input v-model="form.ipWhitelist" type="textarea" :rows="4" placeholder="每行一个 IP 或 CIDR；留空表示不限制" />
            <div class="subtitle">配置由服务端保存；是否执行访问拦截取决于部署端中间件。</div>
          </el-form-item>
        </el-form>
      </el-card>
      <el-card class="stacked-card" shadow="never" v-loading="loading">
        <template #header>
          <div>
            <div class="title">AI 图片生成</div>
            <div class="subtitle">聊天 AI 判断需要图片后，使用独立的 GPT Image 2 API 生成并发送。</div>
          </div>
        </template>
        <el-alert
          v-if="!imageKeyConfigured"
          class="image-key-alert"
          title="尚未配置 GPT Image 2 API Key，请先前往 API Key 管理新增 GPT Image 2。"
          type="warning"
          :closable="false"
        />
        <el-form :model="form" label-position="top">
          <el-form-item label="启用聊天生图">
            <el-switch v-model="form.imageGenerationEnabled" />
          </el-form-item>
          <el-form-item label="图片模型">
            <el-input v-model="form.imageGenerationModel" placeholder="gpt-image-2" />
            <div class="subtitle">新项目推荐使用 gpt-image-2；也可按兼容 API 的实际模型名调整。</div>
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="生成质量">
                <el-select v-model="form.imageGenerationQuality" style="width:100%">
                  <el-option label="低（更快）" value="low" />
                  <el-option label="中（推荐）" value="medium" />
                  <el-option label="高" value="high" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="默认画幅">
                <el-select v-model="form.imageGenerationSize" style="width:100%">
                  <el-option label="AI 自动选择" value="auto" />
                  <el-option label="方形 1024×1024" value="1024x1024" />
                  <el-option label="竖版 1024×1536" value="1024x1536" />
                  <el-option label="横版 1536×1024" value="1536x1024" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </el-card>
    </el-col>
    <el-col :xs="24" :lg="10">
      <el-card shadow="never">
        <template #header><span class="title">表情识别设置</span></template>
        <el-form :model="form" label-position="top">
          <el-form-item label="默认识别模型">
            <el-select v-model="form.emojiRecognitionModel" filterable style="width:100%">
              <el-option v-for="model in visionModels" :key="model.model_id" :label="model.display_name || model.model_id" :value="model.model_id" />
            </el-select>
            <div v-if="!visionModels.length" class="warning">当前没有已启用的视觉模型，请先在模型管理中配置。</div>
          </el-form-item>
          <el-form-item label="自定义识别 Prompt">
            <el-input v-model="form.emojiRecognizePrompt" type="textarea" :rows="10" placeholder="留空时使用服务端默认 Prompt" />
          </el-form-item>
        </el-form>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../http.js'

const loading = ref(false)
const saving = ref(false)
const visionModels = ref([])
const imageKeyConfigured = ref(false)
const form = ref({
  defaultDailyQuota: 50,
  quotaExhaustedMessage: '今日聊天配额已用完，请明天再来。',
  emojiRecognitionModel: '',
  emojiRecognizePrompt: '',
  ipWhitelist: '',
  imageGenerationEnabled: true,
  imageGenerationModel: 'gpt-image-2',
  imageGenerationQuality: 'medium',
  imageGenerationSize: 'auto',
})

function decode(value, fallback) {
  if (value == null) return fallback
  if (typeof value !== 'string') return value
  try { return JSON.parse(value) } catch { return value }
}

async function load() {
  loading.value = true
  try {
    const [settings, models, apiKeys] = await Promise.all([
      http.get('/admin/settings'),
      http.get('/admin/models'),
      http.get('/admin/api-keys'),
    ])
    form.value = {
      defaultDailyQuota: Number(decode(settings.defaultDailyQuota ?? settings.default_daily_quota, 50)),
      quotaExhaustedMessage: String(decode(settings.quotaExhaustedMessage ?? settings.quota_exhausted_message, '今日聊天配额已用完，请明天再来。')),
      emojiRecognitionModel: String(decode(settings.emojiRecognitionModel ?? settings.emoji_recognition_model, '')),
      emojiRecognizePrompt: String(decode(settings.emojiRecognizePrompt ?? settings.emoji_recognize_prompt, '')),
      ipWhitelist: Array.isArray(decode(settings.ipWhitelist ?? settings.ip_whitelist, [])) ? decode(settings.ipWhitelist ?? settings.ip_whitelist, []).join('\n') : String(decode(settings.ipWhitelist ?? settings.ip_whitelist, '')),
      imageGenerationEnabled: Boolean(decode(settings.imageGenerationEnabled ?? settings.image_generation_enabled, true)),
      imageGenerationModel: String(decode(settings.imageGenerationModel ?? settings.image_generation_model, 'gpt-image-2')),
      imageGenerationQuality: String(decode(settings.imageGenerationQuality ?? settings.image_generation_quality, 'medium')),
      imageGenerationSize: String(decode(settings.imageGenerationSize ?? settings.image_generation_size, 'auto')),
    }
    visionModels.value = models.filter(model => model.supports_vision && model.is_enabled)
    imageKeyConfigured.value = apiKeys.some(key => key.provider === 'gpt-image' && key.is_active)
  } catch (error) {
    ElMessage.error(`设置加载失败：${error}`)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!Number.isInteger(form.value.defaultDailyQuota) || form.value.defaultDailyQuota < 0) {
    ElMessage.warning('每日配额必须是非负整数')
    return
  }
  saving.value = true
  try {
    await http.put('/admin/settings', {
      default_daily_quota: form.value.defaultDailyQuota,
      quota_exhausted_message: form.value.quotaExhaustedMessage,
      emoji_recognition_model: form.value.emojiRecognitionModel,
      emoji_recognize_prompt: form.value.emojiRecognizePrompt,
      ip_whitelist: form.value.ipWhitelist.split(/\r?\n/).map(value => value.trim()).filter(Boolean),
      image_generation_enabled: form.value.imageGenerationEnabled,
      image_generation_model: form.value.imageGenerationModel.trim() || 'gpt-image-2',
      image_generation_quality: form.value.imageGenerationQuality,
      image_generation_size: form.value.imageGenerationSize,
    })
    ElMessage.success('系统设置已保存')
  } catch (error) {
    ElMessage.error(`保存失败：${error}`)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.header-row { display:flex; justify-content:space-between; align-items:center; gap:16px; }
.title { font-weight:650; color:var(--miao-ink); }
.subtitle, .hint { color:var(--miao-ink-secondary); font-size:12px; margin-top:4px; }
.hint { margin-left:10px; }
.warning { color:#9a692f; font-size:12px; margin-top:6px; }
.stacked-card { margin-top:16px; }
.image-key-alert { margin-bottom:16px; }
@media (max-width: 991px) { .el-col + .el-col { margin-top:16px; } }
</style>
