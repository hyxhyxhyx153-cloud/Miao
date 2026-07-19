import { pool } from '../db/client.js'
import { getProviderEnvName, writeProviderApiKeyToEnv } from '../config/apiKeyEnv.js'
import Anthropic from '@anthropic-ai/sdk'
import OpenAI from 'openai'
import { decryptSecret, encryptSecret } from '../services/secretVault.js'
import { recordAudit } from '../services/audit.js'
import bcrypt from 'bcrypt'
import { getProviderKey, invalidateProviderKeyCache } from './chat.js'
import { validateReferenceImageUrls } from '../services/imageGeneration.js'
import { ensurePersonaEmotionPrompt } from '../services/personaPrompt.js'
import {
  PROVIDER_BASE_URLS,
  classifyProviderError,
  invalidProviderResponse,
  normalizeConfiguredBaseUrl,
  normalizeProviderBaseUrl,
  providerErrorPayload,
  providerFetch,
  providerValidationTimeoutMs,
  withProviderBaseUrlFallback,
} from '../services/providerApi.js'

function createProviderClient(provider, apiKey, baseUrl) {
  const options = {
    apiKey: apiKey.trim(),
    baseURL: normalizeProviderBaseUrl(provider, baseUrl),
    timeout: providerValidationTimeoutMs(),
    maxRetries: 0,
    fetch: providerFetch,
  }
  if (provider === 'anthropic') {
    return new Anthropic(options)
  }
  if (!PROVIDER_BASE_URLS[provider]) throw new Error('Unsupported API key provider')
  return new OpenAI(options)
}

export async function listProviderModels(provider, apiKey, baseUrl) {
  return withProviderBaseUrlFallback(provider, baseUrl, async resolvedBaseUrl => {
    const client = createProviderClient(provider, apiKey, resolvedBaseUrl)
    const page = await client.models.list()
    if (
      !page?.body
      || typeof page.body !== 'object'
      || !Array.isArray(page.body.data)
      || !Array.isArray(page.data)
    ) {
      throw invalidProviderResponse('模型接口返回的内容不是兼容的 JSON 模型列表')
    }
    const models = page.data
      .filter(model => typeof model?.id === 'string')
      .filter(model => provider !== 'gpt-image' || model.id.startsWith('gpt-image-'))
      .map(model => ({
        id: model.id,
        displayName: model.display_name || model.id,
        createdAt: model.created_at || model.created || null,
      }))
      .sort((a, b) => a.id.localeCompare(b.id))
    if (provider === 'gpt-image' && !models.some(model => model.id === 'gpt-image-2')) {
      models.unshift({ id: 'gpt-image-2', displayName: 'GPT Image 2', createdAt: null })
    }
    return models
  })
}

async function storeDiscoveredModels(provider, models) {
  if (provider === 'gpt-image') return
  for (const model of models) {
    await pool.query(
      `INSERT INTO model_configs(model_id,provider,display_name,is_enabled)
       VALUES($1,$2,$3,true)
       ON CONFLICT(model_id) DO UPDATE SET
         provider=EXCLUDED.provider,
         display_name=COALESCE(model_configs.display_name,EXCLUDED.display_name),
         updated_at=now()`,
      [model.id, provider, model.displayName || model.id]
    )
  }
}

async function syncProviderApiKeyConfig(provider) {
  invalidateProviderKeyCache(provider)
  const { rows } = await pool.query(
    `SELECT api_key FROM api_keys
     WHERE provider=$1 AND is_active=true
     ORDER BY created_at DESC LIMIT 1`,
    [provider]
  )
  const apiKey = rows[0]?.api_key ? decryptSecret(rows[0].api_key) : ''
  try {
    await writeProviderApiKeyToEnv(provider, apiKey)
    return null
  } catch {
    // The database is authoritative at runtime. A read-only or containerized
    // .env file must not turn a successful database save into a failed request.
    return '密钥已保存并立即生效，但服务器无法写入 .env；重启后仍会从数据库自动恢复'
  }
}

export default async function adminRoutes(fastify) {
  const adminAuth = { onRequest: [fastify.authenticate, fastify.requireAdmin] }

  // ── Stats ──────────────────────────────────────────────────────────────────

  fastify.get('/admin/stats', adminAuth, async () => {
    const [users, msgs, mems] = await Promise.all([
      pool.query(`SELECT COUNT(*) FROM users`),
      pool.query(`SELECT COUNT(*) FROM messages WHERE created_at > now() - interval '1 day'`),
      pool.query(`SELECT COUNT(*) FROM memories`),
    ])
    return {
      totalUsers: parseInt(users.rows[0].count),
      todayMessages: parseInt(msgs.rows[0].count),
      totalMemories: parseInt(mems.rows[0].count),
    }
  })

  // ── Users ──────────────────────────────────────────────────────────────────

  fastify.get('/admin/users', adminAuth, async (req) => {
    const { limit = 20, offset = 0 } = req.query
    const { rows } = await pool.query(
      `SELECT id,username,email,nickname,is_active,is_banned,daily_quota,quota_used,created_at
       FROM users ORDER BY created_at DESC LIMIT $1 OFFSET $2`,
      [limit, offset]
    )
    return rows
  })

  fastify.get('/admin/dashboard', adminAuth, async () => {
    const [summary, daily, models, hourly] = await Promise.all([
      pool.query(`SELECT
        (SELECT COUNT(*)::int FROM users) AS total_users,
        (SELECT COUNT(*)::int FROM users WHERE created_at>=current_date) AS new_users_today,
        (SELECT COUNT(DISTINCT c.user_id)::int FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.created_at>=current_date) AS active_users_today,
        (SELECT COUNT(*)::int FROM messages WHERE created_at>=current_date) AS today_messages,
        (SELECT COUNT(*)::int FROM memories WHERE is_active=true) AS total_memories,
        COALESCE((SELECT SUM(input_tokens+output_tokens)::bigint FROM request_logs WHERE created_at>=current_date),0) AS today_tokens,
        COALESCE((SELECT ROUND(AVG(duration_ms))::int FROM request_logs WHERE created_at>=current_date),0) AS avg_duration_ms,
        COALESCE((SELECT ROUND(100.0*COUNT(*) FILTER (WHERE status='error')/NULLIF(COUNT(*),0),2) FROM request_logs WHERE created_at>=current_date),0) AS error_rate`),
      pool.query(`WITH days AS (
          SELECT generate_series(current_date-6,current_date,interval '1 day')::date AS day
        ) SELECT d.day,
          COUNT(l.id)::int AS requests,
          COALESCE(SUM(l.input_tokens+l.output_tokens),0)::bigint AS tokens,
          COUNT(l.id) FILTER (WHERE l.status='error')::int AS errors,
          COALESCE(ROUND(AVG(l.duration_ms))::int,0) AS avg_duration_ms,
          (SELECT COUNT(*)::int FROM messages m WHERE m.created_at>=d.day AND m.created_at<d.day+1) AS messages,
          (SELECT COUNT(DISTINCT c.user_id)::int FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.created_at>=d.day AND m.created_at<d.day+1) AS active_users
        FROM days d LEFT JOIN request_logs l ON l.created_at>=d.day AND l.created_at<d.day+1
        GROUP BY d.day ORDER BY d.day`),
      pool.query(`SELECT COALESCE(model_id,'unknown') AS model_id,COUNT(*)::int AS count,
          COALESCE(SUM(input_tokens+output_tokens),0)::bigint AS tokens
        FROM request_logs WHERE created_at>=current_date-6
        GROUP BY model_id ORDER BY count DESC`),
      pool.query(`WITH hours AS (
          SELECT generate_series(date_trunc('hour',now())-interval '23 hours',date_trunc('hour',now()),interval '1 hour') AS hour
        ) SELECT h.hour,COUNT(l.id)::int AS requests
        FROM hours h LEFT JOIN request_logs l ON l.created_at>=h.hour AND l.created_at<h.hour+interval '1 hour'
        GROUP BY h.hour ORDER BY h.hour`),
    ])
    return {
      stats: summary.rows[0],
      daily: daily.rows,
      modelUsage: models.rows,
      hourly: hourly.rows,
    }
  })

  fastify.get('/admin/users/:id', adminAuth, async (req, reply) => {
    const { rows } = await pool.query(
      `SELECT u.*,
        (SELECT COUNT(*)::int FROM conversations c WHERE c.user_id=u.id) AS conversation_count,
        (SELECT COUNT(*)::int FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE c.user_id=u.id) AS message_count,
        (SELECT COUNT(*)::int FROM memories me WHERE me.user_id=u.id AND me.is_active=true) AS memory_count
       FROM users u WHERE u.id=$1`,
      [req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'User not found' })
    const { password_hash, ...user } = rows[0]
    return user
  })

  fastify.patch('/admin/users/:id', adminAuth, async (req, reply) => {
    const { isBanned, dailyQuota } = req.body
    const { rows } = await pool.query(
      `UPDATE users SET
         is_banned = COALESCE($1, is_banned),
         daily_quota = COALESCE($2, daily_quota),
         updated_at = now()
       WHERE id=$3 RETURNING id,username,is_banned,daily_quota`,
      [isBanned, dailyQuota, req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'User not found' })
    await recordAudit(req, 'user.update', 'user', req.params.id, { isBanned, dailyQuota })
    return rows[0]
  })

  // ── API Keys ───────────────────────────────────────────────────────────────

  fastify.get('/admin/api-keys', adminAuth, async () => {
    const { rows } = await pool.query(
      `SELECT k.id,k.provider,k.api_key,k.base_url,k.is_active,k.note,k.alert_threshold,k.created_at,
        COALESCE(SUM(l.input_tokens+l.output_tokens) FILTER (WHERE l.created_at>=current_date),0)::bigint AS today_tokens
       FROM api_keys k LEFT JOIN request_logs l ON l.api_key_id=k.id
       GROUP BY k.id ORDER BY k.provider`
    )
    return rows.map(({ api_key, ...row }) => {
      const plain = decryptSecret(api_key)
      return { ...row, api_key_preview: `${plain.slice(0, 8)}****` }
    })
  })

  fastify.post('/admin/api-keys', adminAuth, async (req, reply) => {
    const { provider, apiKey, baseUrl, note, alertThreshold } = req.body
    if (!getProviderEnvName(provider)) {
      return reply.code(400).send({ error: 'Unsupported API key provider' })
    }
    if (typeof apiKey !== 'string' || !apiKey.trim()) {
      return reply.code(400).send({ error: 'API key is required' })
    }
    if (apiKey.trim().length > 8192) {
      return reply.code(400).send({ error: 'API key is too long' })
    }
    let normalizedBaseUrl
    try {
      normalizedBaseUrl = normalizeConfiguredBaseUrl(provider, baseUrl)
    } catch (error) {
      const failure = classifyProviderError(error)
      return reply.code(failure.statusCode).send(providerErrorPayload(error))
    }
    const { rows } = await pool.query(
      `INSERT INTO api_keys(provider,api_key,base_url,note,alert_threshold) VALUES($1,$2,$3,$4,$5) RETURNING id,provider,is_active`,
      [provider, encryptSecret(apiKey.trim()), normalizedBaseUrl, note || null, alertThreshold || null]
    )
    const warning = await syncProviderApiKeyConfig(provider)
    await recordAudit(req, 'api_key.create', 'api_key', rows[0].id, { provider })
    return { ...rows[0], base_url: normalizedBaseUrl, warning }
  })

  fastify.patch('/admin/api-keys/:id', adminAuth, async (req, reply) => {
    const { apiKey, baseUrl, isActive, note, alertThreshold } = req.body
    const { rows: currentRows } = await pool.query(
      `SELECT provider FROM api_keys WHERE id=$1`,
      [req.params.id]
    )
    if (!currentRows[0]) return reply.code(404).send({ error: 'Key not found' })
    const provider = currentRows[0].provider
    const hasApiKey = typeof apiKey === 'string' && Boolean(apiKey.trim())
    if (apiKey != null && typeof apiKey !== 'string') {
      return reply.code(400).send({ error: 'API key must be a string' })
    }
    if (hasApiKey && apiKey.trim().length > 8192) {
      return reply.code(400).send({ error: 'API key is too long' })
    }
    const hasBaseUrl = Object.hasOwn(req.body, 'baseUrl')
    let normalizedBaseUrl = null
    try {
      if (hasBaseUrl) normalizedBaseUrl = normalizeConfiguredBaseUrl(provider, baseUrl)
    } catch (error) {
      const failure = classifyProviderError(error)
      return reply.code(failure.statusCode).send(providerErrorPayload(error))
    }
    const { rows } = await pool.query(
      `UPDATE api_keys SET
         api_key = CASE WHEN $1 THEN $2 ELSE api_key END,
         base_url = CASE WHEN $3 THEN $4 ELSE base_url END,
         is_active = COALESCE($5, is_active),
         note = COALESCE($6, note),
         alert_threshold = COALESCE($7, alert_threshold),
         updated_at = now()
       WHERE id=$8 RETURNING id,provider,base_url,is_active`,
      [
        hasApiKey,
        hasApiKey ? encryptSecret(apiKey.trim()) : null,
        hasBaseUrl,
        normalizedBaseUrl,
        isActive,
        note,
        alertThreshold,
        req.params.id,
      ]
    )
    const warning = await syncProviderApiKeyConfig(rows[0].provider)
    await recordAudit(req, 'api_key.update', 'api_key', rows[0].id, { provider: rows[0].provider, isActive })
    return { ...rows[0], warning }
  })

  fastify.delete('/admin/api-keys/:id', adminAuth, async (req, reply) => {
    const { rows } = await pool.query(
      `DELETE FROM api_keys WHERE id=$1 RETURNING provider`,
      [req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Key not found' })
    const warning = await syncProviderApiKeyConfig(rows[0].provider)
    await recordAudit(req, 'api_key.delete', 'api_key', req.params.id, { provider: rows[0].provider })
    return { success: true, warning }
  })

  // Validate credentials before saving a new key and return the models visible to it.
  fastify.post('/admin/api-keys/validate', adminAuth, async (req, reply) => {
    const { provider, apiKey, baseUrl } = req.body
    if (!getProviderEnvName(provider) || typeof apiKey !== 'string' || !apiKey.trim()) {
      return reply.code(400).send({ error: 'Provider and API key are required' })
    }
    try {
      const models = await listProviderModels(provider, apiKey, baseUrl)
      await storeDiscoveredModels(provider, models)
      return { success: true, provider, models }
    } catch (err) {
      const failure = classifyProviderError(err)
      return reply.code(failure.statusCode).send(providerErrorPayload(err))
    }
  })

  fastify.get('/admin/api-keys/:id/models', adminAuth, async (req, reply) => {
    const { rows } = await pool.query(
      `SELECT provider, api_key, base_url FROM api_keys WHERE id=$1`,
      [req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Key not found' })
    try {
      const row = rows[0]
      const models = await listProviderModels(row.provider, decryptSecret(row.api_key), row.base_url)
      await storeDiscoveredModels(row.provider, models)
      return {
        provider: row.provider,
        models,
      }
    } catch (err) {
      const failure = classifyProviderError(err)
      return reply.code(failure.statusCode).send(providerErrorPayload(err))
    }
  })

  // API key connectivity test
  fastify.post('/admin/api-keys/:id/test', adminAuth, async (req, reply) => {
    const { rows } = await pool.query(
      `SELECT provider, api_key, base_url FROM api_keys WHERE id=$1`,
      [req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Key not found' })
    const { provider, api_key, base_url } = rows[0]

    try {
      const models = await listProviderModels(provider, decryptSecret(api_key), base_url)
      return { success: true, provider, modelCount: models.length }
    } catch (err) {
      const failure = classifyProviderError(err)
      return reply.code(failure.statusCode).send(providerErrorPayload(err))
    }
  })

  // ── Models ─────────────────────────────────────────────────────────────────

  fastify.get('/admin/models', adminAuth, async () => {
    const { rows } = await pool.query(
      `SELECT model_id,provider,display_name,supports_vision,description,is_enabled,updated_at
       FROM model_configs ORDER BY provider,model_id`
    )
    return rows
  })

  fastify.patch('/admin/models/:modelId', adminAuth, async (req) => {
    const { provider, displayName, supportsVision, description, isEnabled } = req.body
    const { rows } = await pool.query(
      `INSERT INTO model_configs(model_id,provider,display_name,supports_vision,description,is_enabled)
       VALUES($1,$2,$3,$4,$5,$6)
       ON CONFLICT(model_id) DO UPDATE SET
         provider=COALESCE(EXCLUDED.provider,model_configs.provider),
         display_name=COALESCE(EXCLUDED.display_name,model_configs.display_name),
         supports_vision=COALESCE(EXCLUDED.supports_vision,model_configs.supports_vision),
         description=COALESCE(EXCLUDED.description,model_configs.description),
         is_enabled=EXCLUDED.is_enabled,
         updated_at=now()
       RETURNING model_id,provider,display_name,supports_vision,description,is_enabled,updated_at`,
      [req.params.modelId, provider || null, displayName || null, supportsVision, description, isEnabled]
    )
    await recordAudit(req, 'model.update', 'model', req.params.modelId, { provider, isEnabled })
    return rows[0]
  })

  fastify.post('/admin/models/delete-disabled', adminAuth, async (req, reply) => {
    const body = req.body || {}
    const deleteAllDisabled = body.deleteAllDisabled === true
    const rawModelIds = body.modelIds

    if (!deleteAllDisabled && (!Array.isArray(rawModelIds) || rawModelIds.length === 0)) {
      return reply.code(400).send({ error: '请选择要删除的未启用模型' })
    }
    if (Array.isArray(rawModelIds) && rawModelIds.length > 500) {
      return reply.code(400).send({ error: '单次最多处理 500 个模型' })
    }
    if (Array.isArray(rawModelIds) && rawModelIds.some(modelId => (
      typeof modelId !== 'string' || !modelId.trim() || modelId.trim().length > 128
    ))) {
      return reply.code(400).send({ error: '模型 ID 格式无效' })
    }

    const requestedModelIds = Array.isArray(rawModelIds)
      ? [...new Set(rawModelIds.map(modelId => modelId.trim()))]
      : []
    const client = await pool.connect()
    let deletedModelIds = []
    let skipped = []
    let requestedCount = 0

    try {
      await client.query('BEGIN')
      const { rows: modelRows } = deleteAllDisabled
        ? await client.query(
          `SELECT mc.model_id,mc.is_enabled,
                  (SELECT COUNT(*)::int FROM conversations c WHERE c.model_id=mc.model_id) AS conversation_count
           FROM model_configs mc
           WHERE mc.is_enabled=false
           ORDER BY mc.model_id
           FOR UPDATE OF mc`
        )
        : await client.query(
          `SELECT mc.model_id,mc.is_enabled,
                  (SELECT COUNT(*)::int FROM conversations c WHERE c.model_id=mc.model_id) AS conversation_count
           FROM model_configs mc
           WHERE mc.model_id=ANY($1::text[])
           ORDER BY mc.model_id
           FOR UPDATE OF mc`,
          [requestedModelIds]
        )

      requestedCount = deleteAllDisabled ? modelRows.length : requestedModelIds.length
      const foundIds = new Set(modelRows.map(row => row.model_id))
      if (!deleteAllDisabled) {
        skipped.push(...requestedModelIds
          .filter(modelId => !foundIds.has(modelId))
          .map(modelId => ({ modelId, reason: 'not_found', conversationCount: 0 })))
      }

      const candidates = []
      for (const row of modelRows) {
        if (row.is_enabled) {
          skipped.push({ modelId: row.model_id, reason: 'enabled', conversationCount: 0 })
        } else if (row.conversation_count > 0) {
          skipped.push({ modelId: row.model_id, reason: 'in_use', conversationCount: row.conversation_count })
        } else {
          candidates.push(row.model_id)
        }
      }

      if (candidates.length > 0) {
        const { rows: deletedRows } = await client.query(
          `DELETE FROM model_configs mc
           WHERE mc.model_id=ANY($1::text[])
             AND mc.is_enabled=false
             AND NOT EXISTS(SELECT 1 FROM conversations c WHERE c.model_id=mc.model_id)
           RETURNING mc.model_id`,
          [candidates]
        )
        deletedModelIds = deletedRows.map(row => row.model_id)

        const deletedSet = new Set(deletedModelIds)
        const racedIds = candidates.filter(modelId => !deletedSet.has(modelId))
        if (racedIds.length > 0) {
          const { rows: racedRows } = await client.query(
            `SELECT mc.model_id,mc.is_enabled,
                    (SELECT COUNT(*)::int FROM conversations c WHERE c.model_id=mc.model_id) AS conversation_count
             FROM model_configs mc WHERE mc.model_id=ANY($1::text[])`,
            [racedIds]
          )
          const racedById = new Map(racedRows.map(row => [row.model_id, row]))
          skipped.push(...racedIds.map(modelId => {
            const row = racedById.get(modelId)
            return {
              modelId,
              reason: row?.is_enabled ? 'enabled' : row?.conversation_count > 0 ? 'in_use' : 'not_found',
              conversationCount: row?.conversation_count || 0,
            }
          }))
        }
      }

      await client.query('COMMIT')
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      throw error
    } finally {
      client.release()
    }

    skipped.sort((a, b) => a.modelId.localeCompare(b.modelId))
    await recordAudit(req, 'model.delete_disabled', 'model', null, {
      mode: deleteAllDisabled ? 'all_disabled' : 'selected',
      requestedCount,
      deletedModelIds,
      skipped,
    })
    return {
      success: true,
      requestedCount,
      deletedCount: deletedModelIds.length,
      deletedModelIds,
      skippedCount: skipped.length,
      skipped,
    }
  })


  // ── Emojis batch recognize ─────────────────────────────────────────────────

  fastify.post('/admin/emojis/batch-recognize', adminAuth, async (req, reply) => {
    const { url } = req.body || {}
    if (!url) return reply.code(400).send({ error: 'url is required' })

    const { rows: settingRows } = await pool.query(
      `SELECT key,value FROM system_settings WHERE key IN('emoji_recognition_model','emoji_recognize_prompt')`
    )
    const settings = Object.fromEntries(settingRows.map(row => [row.key, row.value]))
    const model = req.body?.model || settings.emoji_recognition_model || 'claude-sonnet-4-6'
    const { rows: modelRows } = await pool.query(
      `SELECT provider,supports_vision,is_enabled FROM model_configs WHERE model_id=$1`, [model]
    )
    const config = modelRows[0]
    if (!config?.is_enabled || !config.supports_vision) {
      return reply.code(400).send({ error: 'Selected model is unavailable or does not support vision' })
    }
    const prompt = settings.emoji_recognize_prompt || `Analyze this emoji/sticker image and return JSON with exactly these fields:
- emotionTag: one of: happy, excited, curious, shy, embarrassed, caring, gentle, playful, thinking, surprised, sad, nervous, proud, sleepy, angry
- description: short Chinese description of the image (max 30 chars)
- confidence: number 0-1

Respond with JSON only, no markdown.`

    try {
      const credential = await getProviderKey(config.provider)
      if (!credential) return reply.code(503).send({ error: `No active ${config.provider} key` })
      if (config.provider === 'anthropic') {
        const client = new Anthropic({ apiKey: credential.apiKey, baseURL: credential.baseUrl || undefined })
        const msg = await client.messages.create({
          model,
          max_tokens: 200,
          messages: [{
            role: 'user',
            content: [
              { type: 'image', source: { type: 'url', url } },
              { type: 'text', text: prompt },
            ],
          }],
        })
        const text = msg.content[0]?.text || '{}'
        const parsed = JSON.parse(text.replace(/```json?|```/g, '').trim())
        return { emotionTag: parsed.emotionTag || 'happy', description: parsed.description || '', confidence: parsed.confidence ?? 0.8 }
      } else {
        const res = await withProviderBaseUrlFallback(config.provider, credential.baseUrl, async baseURL => {
          const client = createProviderClient(config.provider, credential.apiKey, baseURL)
          const response = await client.chat.completions.create({
            model,
            max_tokens: 200,
            messages: [{
              role: 'user',
              content: [
                { type: 'image_url', image_url: { url } },
                { type: 'text', text: prompt },
              ],
            }],
          })
          if (!response || typeof response !== 'object' || !Array.isArray(response.choices)) {
            throw invalidProviderResponse('表情识别接口返回了非兼容的 JSON 数据')
          }
          return response
        })
        const text = res.choices[0]?.message?.content || '{}'
        const parsed = JSON.parse(text.replace(/```json?|```/g, '').trim())
        return { emotionTag: parsed.emotionTag || 'happy', description: parsed.description || '', confidence: parsed.confidence ?? 0.8 }
      }
    } catch (err) {
      return reply.code(502).send({ error: err.message || 'AI recognition failed' })
    }
  })

  // ── Personas (admin) ───────────────────────────────────────────────────────

  fastify.get('/admin/personas', adminAuth, async () => {
    const { rows } = await pool.query(
      `SELECT * FROM personas ORDER BY is_builtin DESC, created_at ASC`
    )
    return rows
  })

  fastify.post('/admin/personas', adminAuth, async (req, reply) => {
    const { name, description, system_prompt } = req.body || {}
    if (!String(name || '').trim() || !String(system_prompt || '').trim()) {
      return reply.code(400).send({ error: '名称和系统提示词不能为空' })
    }
    let referenceImageUrls
    try {
      referenceImageUrls = validateReferenceImageUrls(req.body.reference_image_urls ?? req.body.referenceImageUrls)
    } catch (error) {
      return reply.code(400).send({ error: error.message })
    }
    const { rows } = await pool.query(
      `INSERT INTO personas(name, description, system_prompt, reference_image_urls, is_builtin)
       VALUES($1,$2,$3,$4,true) RETURNING *`,
      [name.trim(), description || '', ensurePersonaEmotionPrompt(system_prompt), referenceImageUrls]
    )
    await recordAudit(req, 'persona.create', 'persona', rows[0].id, { name, referenceImageCount: referenceImageUrls.length })
    return rows[0]
  })

  fastify.patch('/admin/personas/:id', adminAuth, async (req, reply) => {
    const { name, description } = req.body || {}
    const hasDescription = Object.prototype.hasOwnProperty.call(req.body || {}, 'description')
    const hasPrompt = Object.prototype.hasOwnProperty.call(req.body || {}, 'system_prompt')
      || Object.prototype.hasOwnProperty.call(req.body || {}, 'systemPrompt')
    const systemPrompt = req.body?.system_prompt ?? req.body?.systemPrompt
    if (name !== undefined && (typeof name !== 'string' || !name.trim())) {
      return reply.code(400).send({ error: '名称不能为空' })
    }
    if (hasPrompt && (typeof systemPrompt !== 'string' || !systemPrompt.trim())) {
      return reply.code(400).send({ error: '系统提示词不能为空' })
    }
    if (description !== undefined && description !== null && typeof description !== 'string') {
      return reply.code(400).send({ error: '描述格式不正确' })
    }
    const hasReferenceImages = Object.prototype.hasOwnProperty.call(req.body || {}, 'reference_image_urls')
      || Object.prototype.hasOwnProperty.call(req.body || {}, 'referenceImageUrls')
    let referenceImageUrls = null
    if (hasReferenceImages) {
      try {
        referenceImageUrls = validateReferenceImageUrls(req.body.reference_image_urls ?? req.body.referenceImageUrls)
      } catch (error) {
        return reply.code(400).send({ error: error.message })
      }
    }
    const { rows } = await pool.query(
      `UPDATE personas SET
         name = CASE WHEN $1::boolean THEN $2 ELSE name END,
         description = CASE WHEN $3::boolean THEN $4 ELSE description END,
         system_prompt = CASE WHEN $5::boolean THEN $6 ELSE system_prompt END,
         reference_image_urls = CASE WHEN $7::boolean THEN $8::text[] ELSE reference_image_urls END,
         updated_at = now()
       WHERE id=$9 RETURNING *`,
      [
        name !== undefined, name === undefined ? null : name.trim(),
        hasDescription, description ?? '',
        hasPrompt, hasPrompt ? ensurePersonaEmotionPrompt(systemPrompt) : null,
        hasReferenceImages, referenceImageUrls, req.params.id,
      ]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Not found' })
    await recordAudit(req, 'persona.update', 'persona', req.params.id, {
      referenceImageCount: hasReferenceImages ? referenceImageUrls.length : undefined,
    })
    return rows[0]
  })

  fastify.delete('/admin/personas/:id', adminAuth, async (req, reply) => {
    const res = await pool.query(
      `DELETE FROM personas WHERE id=$1 AND id NOT IN(
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000003',
        '00000000-0000-0000-0000-000000000004'
      )`,
      [req.params.id]
    )
    if (res.rowCount === 0) return reply.code(404).send({ error: 'Not found or protected builtin persona' })
    await recordAudit(req, 'persona.delete', 'persona', req.params.id)
    return { success: true }
  })

  // ── System settings and administrators ─────────────────────────────────────

  fastify.get('/admin/settings', adminAuth, async () => {
    const { rows } = await pool.query(`SELECT key,value FROM system_settings ORDER BY key`)
    return Object.fromEntries(rows.map(row => [row.key, row.value]))
  })

  fastify.put('/admin/settings', adminAuth, async (req, reply) => {
    const settings = req.body
    if (!settings || typeof settings !== 'object' || Array.isArray(settings)) {
      return reply.code(400).send({ error: 'Settings must be an object' })
    }
    for (const [key, value] of Object.entries(settings)) {
      await pool.query(
        `INSERT INTO system_settings(key,value) VALUES($1,$2)
         ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value,updated_at=now()`,
        [key, JSON.stringify(value)]
      )
    }
    await recordAudit(req, 'settings.update', 'system_settings', null, { keys: Object.keys(settings) })
    return { success: true }
  })

  fastify.get('/admin/accounts', adminAuth, async () => {
    const { rows } = await pool.query(
      `SELECT id,username,email,nickname,is_active,created_at,updated_at FROM users
       WHERE role='admin' ORDER BY created_at ASC`
    )
    return rows
  })

  fastify.post('/admin/accounts', adminAuth, async (req, reply) => {
    const { username, email, password, nickname } = req.body
    if (!/^[A-Za-z0-9_]{2,32}$/.test(username || '') || !email || !password || password.length < 8) {
      return reply.code(400).send({ error: '管理员账号字段不符合要求' })
    }
    try {
      const hash = await bcrypt.hash(password, 12)
      const { rows } = await pool.query(
        `INSERT INTO users(username,email,password_hash,nickname,role)
         VALUES($1,$2,$3,$4,'admin') RETURNING id,username,email,nickname,is_active,created_at`,
        [username, email.toLowerCase(), hash, nickname || username]
      )
      await recordAudit(req, 'admin.create', 'user', rows[0].id, { username })
      return rows[0]
    } catch (error) {
      if (error.code === '23505') return reply.code(409).send({ error: '用户名或邮箱已存在' })
      throw error
    }
  })

  fastify.patch('/admin/accounts/:id', adminAuth, async (req, reply) => {
    const { nickname, isActive, password } = req.body
    if (req.params.id === req.user.sub && isActive === false) {
      return reply.code(400).send({ error: '不能停用当前登录账号' })
    }
    const hash = password ? await bcrypt.hash(password, 12) : null
    const { rows } = await pool.query(
      `UPDATE users SET nickname=COALESCE($1,nickname),is_active=COALESCE($2,is_active),
       password_hash=COALESCE($3,password_hash),updated_at=now()
       WHERE id=$4 AND role='admin' RETURNING id,username,email,nickname,is_active,updated_at`,
      [nickname, isActive, hash, req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Admin not found' })
    await recordAudit(req, 'admin.update', 'user', req.params.id, { isActive, passwordChanged: Boolean(password) })
    return rows[0]
  })

  fastify.delete('/admin/accounts/:id', adminAuth, async (req, reply) => {
    if (req.params.id === req.user.sub) return reply.code(400).send({ error: '不能删除当前登录账号' })
    const result = await pool.query(`DELETE FROM users WHERE id=$1 AND role='admin'`, [req.params.id])
    if (!result.rowCount) return reply.code(404).send({ error: 'Admin not found' })
    await recordAudit(req, 'admin.delete', 'user', req.params.id)
    return { success: true }
  })

  fastify.get('/admin/audit-logs', adminAuth, async (req) => {
    const { limit = 50, offset = 0, action } = req.query
    const { rows } = await pool.query(
      `SELECT a.*,u.username AS admin_username FROM audit_logs a
       LEFT JOIN users u ON u.id=a.admin_user_id
       WHERE ($1::text IS NULL OR a.action=$1)
       ORDER BY a.created_at DESC LIMIT $2 OFFSET $3`,
      [action || null, limit, offset]
    )
    return rows
  })
}
