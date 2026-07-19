<template>
  <div v-loading="loading" class="dashboard-page">
    <el-alert
      v-if="dashboardError"
      title="仪表盘聚合数据暂时不可用"
      description="服务恢复后刷新页面即可重新加载；当前不会用 0 或 100% 代替未知数据。"
      type="warning"
      :closable="false"
      show-icon
    />
    <section class="hero-metrics" aria-label="关键指标">
      <article v-for="(item, index) in heroCards" :key="item.label" class="hero-card" :class="`hero-card-${index + 1}`">
        <div class="hero-card-top">
          <span class="metric-label">{{ item.label }}</span>
          <span class="metric-icon"><el-icon><component :is="item.icon" /></el-icon></span>
        </div>
        <div class="metric-value">{{ item.value }}</div>
        <div class="metric-note">{{ item.note }}</div>
      </article>
    </section>

    <section class="secondary-strip" aria-label="次要指标">
      <div v-for="item in secondaryMetrics" :key="item.label" class="secondary-metric">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </section>

    <section class="chart-grid">
      <el-card class="line-card" shadow="never">
        <template #header>
          <div class="header-row">
            <div>
              <div class="title">请求趋势</div>
              <div class="subtitle">近 7 天请求与消息量</div>
            </div>
            <el-tag effect="plain">按日聚合</el-tag>
          </div>
        </template>
        <div v-if="daily.length" ref="lineRef" class="chart" aria-label="近 7 天请求与消息量折线图" />
        <el-empty v-else description="近 7 天暂无聚合数据" />
      </el-card>

      <el-card class="usage-card" shadow="never">
        <template #header>
          <div>
            <div class="title">模型使用分布</div>
            <div class="subtitle">近 7 天调用占比</div>
          </div>
        </template>
        <div v-if="modelUsage.length" ref="pieRef" class="chart" aria-label="近 7 天模型使用分布环形图" />
        <el-empty v-else description="近 7 天暂无模型调用数据" />
      </el-card>
    </section>

    <section class="detail-grid">
      <el-card shadow="never">
        <template #header>
          <div>
            <div class="title">表情分类</div>
            <div class="subtitle">素材库存与使用次数</div>
          </div>
        </template>
        <el-table :data="emojiStats" size="small" max-height="280">
          <el-table-column prop="emotion_tag" label="情绪" />
          <el-table-column prop="count" label="总数" />
          <el-table-column prop="active_count" label="启用" />
          <el-table-column prop="send_count" label="发送次数" />
        </el-table>
        <el-empty v-if="!emojiStats.length" description="暂无表情统计" />
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div>
            <div class="title">运行概览</div>
            <div class="subtitle">关键服务和资源可用性</div>
          </div>
        </template>
        <div class="health-list">
          <div class="health-row">
            <span>API 服务</span>
            <el-tag :type="healthOk ? 'success' : 'danger'" effect="light">{{ healthOk ? '运行中' : '异常' }}</el-tag>
          </div>
          <div class="health-row"><span>启用模型</span><strong>{{ enabledModels }} <small>/ {{ models.length }}</small></strong></div>
          <div class="health-row"><span>启用 API Key</span><strong>{{ activeKeys }} <small>/ {{ keys.length }}</small></strong></div>
          <div class="health-row"><span>在线微信 Worker</span><strong>{{ liveWorkers }} <small>/ {{ bindings.length }}</small></strong></div>
          <div class="health-row"><span>近 7 天请求错误率</span><strong>{{ errorRate }}</strong></div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import http from '../http.js'

const loading = ref(false)
const stats = ref(null)
const dashboardError = ref(false)
const daily = ref([])
const modelUsage = ref([])
const emojiStats = ref([])
const models = ref([])
const keys = ref([])
const bindings = ref([])
const healthOk = ref(false)
const lineRef = ref()
const pieRef = ref()
let lineChart
let pieChart
let chartResizeObserver

const enabledModels = computed(() => models.value.filter(model => model.is_enabled).length)
const activeKeys = computed(() => keys.value.filter(key => key.is_active).length)
const liveWorkers = computed(() => bindings.value.filter(binding => binding.worker_live).length)
const totalEmojis = computed(() => emojiStats.value.reduce((sum, row) => sum + Number(row.count || 0), 0))
const errorRate = computed(() => {
  const requests = daily.value.reduce((sum, row) => sum + Number(row.requests || 0), 0)
  const errors = daily.value.reduce((sum, row) => sum + Number(row.errors || 0), 0)
  return requests ? `${(errors / requests * 100).toFixed(1)}%` : '无请求'
})

const heroCards = computed(() => stats.value ? [
  { label: '注册用户', value: Number(stats.value.totalUsers).toLocaleString(), icon: 'User', note: `今日新增 ${stats.value.newUsersToday}` },
  { label: '今日消息', value: Number(stats.value.todayMessages).toLocaleString(), icon: 'ChatDotRound', note: `活跃用户 ${stats.value.activeUsersToday}` },
  { label: '今日 Token', value: Number(stats.value.todayTokens).toLocaleString(), icon: 'Coin', note: '跨模型合计' },
  { label: '请求成功率', value: `${(100 - Number(stats.value.errorRate)).toFixed(1)}%`, icon: 'CircleCheck', note: `平均响应 ${stats.value.avgDurationMs} ms` },
] : [
  { label: '注册用户', value: '—', icon: 'User', note: '数据不可用' },
  { label: '今日消息', value: '—', icon: 'ChatDotRound', note: '数据不可用' },
  { label: '今日 Token', value: '—', icon: 'Coin', note: '数据不可用' },
  { label: '请求成功率', value: '—', icon: 'CircleCheck', note: '数据不可用' },
])

const secondaryMetrics = computed(() => [
  { label: '记忆条数', value: stats.value ? Number(stats.value.totalMemories).toLocaleString() : '—' },
  { label: '表情素材', value: Number(totalEmojis.value).toLocaleString() },
  { label: '启用模型', value: `${enabledModels.value}/${models.value.length}` },
  { label: '有效 Key', value: `${activeKeys.value}/${keys.value.length}` },
  { label: '在线 Worker', value: `${liveWorkers.value}/${bindings.value.length}` },
  { label: '7 日错误率', value: errorRate.value },
])

async function load() {
  loading.value = true
  dashboardError.value = false
  try {
    const results = await Promise.allSettled([
      http.get('/admin/dashboard', { params: { days: 7 } }),
      http.get('/admin/emojis/stats'),
      http.get('/admin/models'),
      http.get('/admin/api-keys'),
      http.get('/admin/wechat/bindings'),
      axios.get('/health'),
    ])
    const failures = results.filter(result => result.status === 'rejected')
    if (results[0].status === 'fulfilled' && results[0].value?.stats) {
      const dashboard = results[0].value || {}
      const serverStats = dashboard.stats || {}
      stats.value = {
        totalUsers: Number(serverStats.total_users ?? serverStats.totalUsers ?? 0),
        newUsersToday: Number(serverStats.new_users_today ?? serverStats.newUsersToday ?? 0),
        activeUsersToday: Number(serverStats.active_users_today ?? serverStats.activeUsersToday ?? 0),
        todayMessages: Number(serverStats.today_messages ?? serverStats.todayMessages ?? 0),
        totalMemories: Number(serverStats.total_memories ?? serverStats.totalMemories ?? 0),
        todayTokens: Number(serverStats.today_tokens ?? serverStats.todayTokens ?? 0),
        avgDurationMs: Number(serverStats.avg_duration_ms ?? serverStats.avgDurationMs ?? 0),
        errorRate: Number(serverStats.error_rate ?? serverStats.errorRate ?? 0),
      }
      daily.value = Array.isArray(dashboard.daily) ? dashboard.daily : []
      modelUsage.value = Array.isArray(dashboard.modelUsage) ? dashboard.modelUsage : []
    } else {
      stats.value = null
      daily.value = []
      modelUsage.value = []
      dashboardError.value = true
    }
    if (results[1].status === 'fulfilled') emojiStats.value = results[1].value
    if (results[2].status === 'fulfilled') models.value = results[2].value
    if (results[3].status === 'fulfilled') keys.value = results[3].value
    if (results[4].status === 'fulfilled') bindings.value = results[4].value
    healthOk.value = results[5].status === 'fulfilled' && results[5].value.data.status === 'ok'
    if (failures.length) ElMessage.warning(`仪表盘有 ${failures.length} 项数据加载失败`)
    await nextTick()
    renderCharts()
    observeChartContainers()
  } finally {
    loading.value = false
  }
}

function renderCharts() {
  const axisLine = { lineStyle: { color: 'rgba(57, 60, 72, .1)' } }
  const labelStyle = { color: '#858893', fontSize: 10 }
  if (daily.value.length && lineRef.value) {
    lineChart ||= echarts.init(lineRef.value)
    lineChart.setOption({
      animationDuration: 600,
      color: ['#6174d8', '#a0a9da'],
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(250,250,252,.94)', borderColor: 'rgba(255,255,255,.9)', textStyle: { color: '#34363e' }, extraCssText: 'border-radius:12px;box-shadow:0 14px 38px rgba(50,54,76,.14);backdrop-filter:blur(16px)' },
      legend: { data: ['请求量', '消息量'], right: 4, top: 0, itemWidth: 18, itemHeight: 7, textStyle: labelStyle },
      grid: { left: 42, right: 15, top: 48, bottom: 27 },
      xAxis: { type: 'category', boundaryGap: false, data: daily.value.map(row => formatDay(row.day)), axisLine, axisTick: { show: false }, axisLabel: labelStyle },
      yAxis: { type: 'value', minInterval: 1, axisLine: { show: false }, axisTick: { show: false }, axisLabel: labelStyle, splitLine: { lineStyle: { color: 'rgba(57,60,72,.065)' } } },
      series: [
        { name: '请求量', type: 'line', smooth: 0.35, symbol: 'circle', symbolSize: 6, data: daily.value.map(row => Number(row.requests || 0)), lineStyle: { width: 2.5 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(97,116,216,.22)' }, { offset: 1, color: 'rgba(97,116,216,0)' }]) } },
        { name: '消息量', type: 'line', smooth: 0.35, symbol: 'circle', symbolSize: 5, data: daily.value.map(row => Number(row.messages || 0)), lineStyle: { width: 2 } },
      ],
    })
  } else {
    lineChart?.dispose()
    lineChart = null
  }

  if (modelUsage.value.length && pieRef.value) {
    pieChart ||= echarts.init(pieRef.value)
    pieChart.setOption({
      animationDuration: 650,
      color: ['#6174d8', '#7f8dde', '#9ba6e5', '#b8c0ec', '#d2d7f3', '#8c91b6'],
      tooltip: { trigger: 'item', backgroundColor: 'rgba(250,250,252,.94)', borderColor: 'rgba(255,255,255,.9)', textStyle: { color: '#34363e' }, extraCssText: 'border-radius:12px;box-shadow:0 14px 38px rgba(50,54,76,.14)' },
      legend: { type: 'scroll', orient: 'vertical', right: 2, top: 'center', itemWidth: 9, itemHeight: 9, icon: 'circle', textStyle: labelStyle },
      series: [{ type: 'pie', radius: ['48%', '72%'], center: ['34%', '50%'], avoidLabelOverlap: true, data: modelUsage.value.map(row => ({ name: row.model_id || row.modelId || '未知模型', value: Number(row.count || 0) })), label: { show: false }, itemStyle: { borderColor: 'rgba(255,255,255,.86)', borderWidth: 3, borderRadius: 5 }, emphasis: { scaleSize: 5 } }],
    })
  } else {
    pieChart?.dispose()
    pieChart = null
  }
}

function formatDay(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${date.getMonth() + 1}/${date.getDate()}`
}
function resize() {
  lineChart?.resize()
  pieChart?.resize()
}

function observeChartContainers() {
  if (!window.ResizeObserver) return
  chartResizeObserver ||= new ResizeObserver(resize)
  if (lineRef.value) chartResizeObserver.observe(lineRef.value)
  if (pieRef.value) chartResizeObserver.observe(pieRef.value)
}

onMounted(() => {
  load()
  window.addEventListener('resize', resize)
})
onUnmounted(() => {
  window.removeEventListener('resize', resize)
  lineChart?.dispose()
  pieChart?.dispose()
  chartResizeObserver?.disconnect()
})
</script>

<style scoped>
.dashboard-page {
  display: grid;
  gap: 16px;
  min-height: 400px;
}

.hero-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.hero-card {
  position: relative;
  min-height: 164px;
  overflow: hidden;
  padding: 20px;
  border: 1px solid rgba(255, 255, 255, 0.84);
  border-radius: 19px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--miao-inner-light), var(--miao-shadow-soft);
  backdrop-filter: blur(16px) saturate(120%);
  -webkit-backdrop-filter: blur(16px) saturate(120%);
  transition: transform 220ms var(--miao-ease), box-shadow 220ms ease;
}

.hero-card::after {
  position: absolute;
  right: -42px;
  bottom: -56px;
  width: 130px;
  height: 130px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(97, 116, 216, 0.12), transparent 68%);
  content: "";
  pointer-events: none;
}

.hero-card:hover {
  box-shadow: var(--miao-inner-light), var(--miao-shadow);
  transform: translateY(-2px);
}

.hero-card-1 {
  background: linear-gradient(145deg, rgba(239, 242, 255, 0.92), rgba(255, 255, 255, 0.8));
}

.hero-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.metric-label {
  color: #686b75;
  font-size: 11px;
  font-weight: 620;
  letter-spacing: 0.025em;
}

.metric-icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid rgba(97, 116, 216, 0.12);
  border-radius: 11px;
  background: rgba(97, 116, 216, 0.09);
  color: var(--miao-accent-deep);
  font-size: 16px;
}

.metric-value {
  position: relative;
  z-index: 1;
  margin-top: 20px;
  color: var(--miao-ink);
  font-size: clamp(25px, 2.3vw, 34px);
  font-weight: 720;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.045em;
  line-height: 1;
}

.metric-note {
  margin-top: 11px;
  color: var(--miao-ink-tertiary);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.secondary-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.82);
  border-radius: 16px;
  background: rgba(250, 250, 252, 0.62);
  box-shadow: var(--miao-inner-light), 0 7px 22px rgba(50, 54, 76, 0.055);
  backdrop-filter: blur(18px) saturate(130%);
  -webkit-backdrop-filter: blur(18px) saturate(130%);
}

.secondary-metric {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  padding: 14px 17px;
  border-right: 1px solid rgba(58, 61, 72, 0.075);
}

.secondary-metric:last-child {
  border-right: 0;
}

.secondary-metric span {
  overflow: hidden;
  color: var(--miao-ink-tertiary);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.secondary-metric strong {
  color: #393b43;
  font-size: 15px;
  font-weight: 660;
  font-variant-numeric: tabular-nums;
}

.chart-grid,
.detail-grid {
  display: grid;
  gap: 16px;
}

.chart-grid {
  grid-template-columns: minmax(0, 1.45fr) minmax(340px, 0.75fr);
}

.detail-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.title {
  color: var(--miao-ink);
  font-weight: 650;
}

.subtitle {
  margin-top: 4px;
  font-size: 11px;
}

.chart {
  height: 310px;
}

.health-list {
  overflow: hidden;
  border: 1px solid rgba(58, 61, 72, 0.07);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.64);
}

.health-row {
  display: flex;
  min-height: 50px;
  align-items: center;
  justify-content: space-between;
  padding: 0 15px;
  border-bottom: 1px solid rgba(58, 61, 72, 0.07);
}

.health-row:last-child {
  border-bottom: 0;
}

.health-row > span {
  color: #62656f;
  font-size: 12px;
}

.health-row strong {
  color: #34363d;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
  font-weight: 650;
}

.health-row small {
  color: var(--miao-ink-tertiary);
  font-size: 10px;
  font-weight: 500;
}

@media (max-width: 1240px) {
  .hero-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .secondary-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .secondary-metric:nth-child(3) {
    border-right: 0;
  }

  .secondary-metric:nth-child(-n + 3) {
    border-bottom: 1px solid rgba(58, 61, 72, 0.075);
  }
}

@media (max-width: 980px) {
  .chart-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .hero-metrics {
    grid-template-columns: 1fr;
  }

  .hero-card {
    min-height: 142px;
  }

  .secondary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .secondary-metric,
  .secondary-metric:nth-child(3) {
    border-right: 1px solid rgba(58, 61, 72, 0.075);
    border-bottom: 1px solid rgba(58, 61, 72, 0.075);
  }

  .secondary-metric:nth-child(even) {
    border-right: 0;
  }

  .secondary-metric:nth-last-child(-n + 2) {
    border-bottom: 0;
  }

  .chart {
    height: 270px;
  }
}
</style>
