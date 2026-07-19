import { pool } from '../db/client.js'
import Anthropic from '@anthropic-ai/sdk'
import OpenAI from 'openai'
import { logRequest } from './logs.js'
import { decryptSecret } from '../services/secretVault.js'
import { createEmbedding, cosineSimilarity } from '../services/embeddings.js'
import { queueAutoMemory } from '../services/autoMemory.js'
import { generatePersonaImage, getImageGenerationConfig } from '../services/imageGeneration.js'
import {
  buildDirectImagePlan,
  buildImagePlannerPrompt,
  isExplicitImageGenerationRequest,
  normalizePostImageReply,
  parseImagePlan,
  shouldRunImageGenerationPlanner,
} from '../services/imageDecision.js'
import { ensurePersonaEmotionPrompt, parsePersonaResponseMeta } from '../services/personaPrompt.js'
import {
  buildEmojiAssistantContent,
  buildEmojiPreview,
  buildEmojiUserContent,
  toModelHistoryMessage,
} from '../services/emojiContext.js'
import {
  buildEmojiCandidateDecisionMessages,
  buildEmojiSendDecisionMessages,
  buildEmojiTagDecisionMessages,
  parseEmojiNumberDecision,
  parseEmojiSendDecision,
  parseEmojiTagDecision,
} from '../services/emojiReplyDecision.js'
import {
  classifyProviderError,
  invalidProviderResponse,
  normalizeProviderBaseUrl,
  providerFetch,
  providerRequestTimeoutMs,
  withProviderBaseUrlFallback,
} from '../services/providerApi.js'

// ── Provider key cache (DB-first, env fallback) ───────────────────────────────

const keyCache = new Map() // provider → { credentials, expiresAt }

export function invalidateProviderKeyCache(provider) {
  keyCache.delete(provider)
}

async function getProviderKeys(provider) {
  const cached = keyCache.get(provider)
  if (cached && cached.expiresAt > Date.now()) {
    return cached.credentials
  }

  // Try DB first (admin-managed keys)
  try {
    const { rows } = await pool.query(
      `SELECT id,api_key,base_url FROM api_keys WHERE provider=$1 AND is_active=true ORDER BY created_at DESC`,
      [provider]
    )
    if (rows.length) {
      const credentials = rows.map(row => ({
        id: row.id,
        apiKey: decryptSecret(row.api_key),
        baseUrl: row.base_url || null,
      }))
      keyCache.set(provider, { credentials, expiresAt: Date.now() + 60_000 })
      return credentials
    }
  } catch {}

  // Fallback to environment variable
  const envKey = {
    anthropic: process.env.ANTHROPIC_API_KEY,
    openai:    process.env.OPENAI_API_KEY,
    deepseek:  process.env.DEEPSEEK_API_KEY,
    qwen:      process.env.QWEN_API_KEY,
    zhipu:     process.env.ZHIPU_API_KEY,
  }[provider]

  if (envKey) return [{ id: null, apiKey: envKey, baseUrl: null }]
  return []
}

export async function getProviderKey(provider) {
  return (await getProviderKeys(provider))[0] || null
}

export async function streamWithCredential({
  provider, modelId, messages, temperature, maxTokens, onDelta, onDone, credentials, signal,
}) {
  const { apiKey, baseUrl: configuredBaseUrl } = credentials
  const clientOptions = {
    apiKey,
    timeout: providerRequestTimeoutMs(),
    maxRetries: 1,
    fetch: providerFetch,
  }

  if (provider === 'anthropic') {
    const client = new Anthropic({
      ...clientOptions,
      baseURL: normalizeProviderBaseUrl(provider, configuredBaseUrl),
    })
    const systemMsg = messages.find(m => m.role === 'system')?.content || ''
    const chatMsgs = messages.filter(m => m.role !== 'system').map(message => {
      if (!message.mediaUrls?.length) return { role: message.role, content: message.content }
      return {
        role: message.role,
        content: [
          ...message.mediaUrls.map(url => ({ type: 'image', source: { type: 'url', url } })),
          { type: 'text', text: message.content || '请分析这些图片' },
        ],
      }
    })

    const stream = await client.messages.stream({
      model: modelId,
      max_tokens: maxTokens,
      temperature,
      system: systemMsg,
      messages: chatMsgs,
    }, { signal })

    let fullText = ''
    for await (const chunk of stream) {
      if (chunk.type === 'content_block_delta' && chunk.delta.type === 'text_delta') {
        fullText += chunk.delta.text
        onDelta(chunk.delta.text)
      }
    }
    const usage = (await stream.finalMessage()).usage
    await onDone(fullText, { inputTokens: usage.input_tokens, outputTokens: usage.output_tokens })
  } else {
    const compatibleMessages = messages.map(message => {
      if (!message.mediaUrls?.length) return { role: message.role, content: message.content }
      return {
        role: message.role,
        content: [
          { type: 'text', text: message.content || '请分析这些图片' },
          ...message.mediaUrls.map(url => ({ type: 'image_url', image_url: { url } })),
        ],
      }
    })
    return withProviderBaseUrlFallback(provider, configuredBaseUrl, async baseURL => {
      const client = new OpenAI({ ...clientOptions, baseURL })
      const stream = await client.chat.completions.create({
        model: modelId,
        messages: compatibleMessages,
        stream: true,
        temperature,
        max_tokens: maxTokens,
        stream_options: { include_usage: true },
      }, { signal })

      let fullText = ''
      let usage = { prompt_tokens: 0, completion_tokens: 0 }
      let sawProtocolChunk = false
      let emittedDelta = false
      try {
        for await (const chunk of stream) {
          if (!chunk || typeof chunk !== 'object' || !Array.isArray(chunk.choices)) {
            throw invalidProviderResponse('聊天接口返回了非兼容的流式数据')
          }
          sawProtocolChunk = true
          const delta = chunk.choices[0]?.delta?.content || ''
          if (delta) {
            fullText += delta
            emittedDelta = true
            onDelta(delta)
          }
          if (chunk.usage) usage = chunk.usage
        }
      } catch (error) {
        if (emittedDelta) error.disableBaseUrlFallback = true
        throw error
      }
      if (!sawProtocolChunk) {
        throw invalidProviderResponse('聊天接口未返回任何兼容的流式数据')
      }
      await onDone(fullText, {
        inputTokens: usage.prompt_tokens || 0,
        outputTokens: usage.completion_tokens || 0,
      })
    })
  }
}

export async function streamAI({ provider, modelId, messages, temperature = 0.8, maxTokens = 4096, onDelta, onDone, onCredential, signal }) {
  const credentialsList = await getProviderKeys(provider)
  if (!credentialsList.length) throw new Error(`${provider} API key not configured`)
  let lastError
  for (const credentials of credentialsList) {
    let emitted = false
    try {
      onCredential?.(credentials.id)
      return await streamWithCredential({
        provider,
        modelId,
        messages,
        temperature,
        maxTokens,
        signal,
        credentials,
        onDelta: delta => {
          emitted = true
          onDelta(delta)
        },
        onDone,
      })
    } catch (error) {
      lastError = error
      if (credentials.id) {
        await pool.query(
          `UPDATE api_keys SET note=CASE WHEN note IS NULL OR note='' THEN $1 ELSE note END,updated_at=now() WHERE id=$2`,
          [`最近调用失败：${String(error.message || error).slice(0, 120)}`, credentials.id]
        ).catch(() => {})
      }
      if (emitted) throw error
    }
  }
  throw lastError || new Error(`${provider} API request failed`)
}

async function completeAI({
  provider,
  modelId,
  messages,
  temperature = 0.4,
  maxTokens = 1200,
  signal,
}) {
  let text = ''
  let usage = { inputTokens: 0, outputTokens: 0 }
  let apiKeyId = null
  await streamAI({
    provider,
    modelId,
    messages,
    temperature,
    maxTokens,
    signal,
    onCredential: id => { apiKeyId = id },
    onDelta: delta => { text += delta },
    onDone: (finalText, finalUsage) => {
      text = finalText
      usage = finalUsage
    },
  })
  return { text, usage, apiKeyId }
}

function createPersonaDeltaFilter(onDelta) {
  let pending = ''
  let suppressingMetadata = false
  return {
    push(delta) {
      if (suppressingMetadata) return
      pending += delta
      const metadataStart = pending.indexOf('<!--')
      if (metadataStart >= 0) {
        const visible = pending.slice(0, metadataStart)
        if (visible) onDelta(visible)
        pending = ''
        suppressingMetadata = true
        return
      }
      const safeLength = Math.max(0, pending.length - 3)
      if (safeLength > 0) {
        onDelta(pending.slice(0, safeLength))
        pending = pending.slice(safeLength)
      }
    },
    finish() {
      if (!suppressingMetadata && pending) onDelta(pending)
      pending = ''
    },
  }
}

function postImageReplySystemPrompt({ systemPrompt, generatedModel }) {
  return `${systemPrompt}

[本轮图片生成结果——最高优先级]
用户要求的图片已经由 ${generatedModel || 'Image 2.0'} 成功生成，并且应用已经把图片发送到了聊天中。现在请以当前人格生成一条全新的、自然且简短的回复：
- 明确知道图片已经生成并发送，不要再说“正在生成”。
- 告诉用户这张图由 Image 2.0 配合生成，可以自然邀请用户查看或提出修改意见。
- 绝对不要声称自己无法生成图片，也不要提 Claude Code、命令行工具、外部网站或让用户改用其他工具。
- 不要重复用户的整段提示词，不要输出技术参数。
- 本轮图片已经完成，末尾 JSON 中 generateImage 必须为 false、imagePrompt 必须为 null，禁止再次触发生图。`
}

// ── Parse emotion/action/image intent from the same AI response ──────────────

export function extractMeta(text) {
  return parsePersonaResponseMeta(text)
}

function publicChatError(error) {
  const message = String(error?.message || '')
  if (/api key not configured/i.test(message)) return '当前模型暂未配置 API Key，请联系管理员'
  const failure = classifyProviderError(error)
  if (failure.code === 'API_DNS_ERROR') return '服务器暂时无法解析模型 API 域名，请联系管理员检查网络或代理'
  if (['API_NETWORK_ERROR', 'API_CONNECTION_REFUSED'].includes(failure.code)) return '服务器暂时无法连接模型 API，请稍后重试'
  if (/quota|rate limit|too many requests|429/i.test(message)) return '请求过于频繁，请稍后再试喵～'
  if (/timeout|timed out|abort/i.test(message)) return '模型响应超时，请稍后重试喵～'
  if (/context length|too many tokens/i.test(message)) return '对话内容太长了，请新建会话后重试喵～'
  return '模型请求失败，请稍后重试或更换模型喵～'
}

function publicImageError(error) {
  const message = String(error?.message || '')
  if (/尚未配置|api key not configured/i.test(message)) return '图片服务尚未配置，请联系管理员设置 GPT Image 2 API'
  const failure = classifyProviderError(error)
  if (failure.code === 'API_DNS_ERROR') return '服务器无法解析图片 API 域名，请联系管理员检查网络或代理'
  if (['API_NETWORK_ERROR', 'API_CONNECTION_REFUSED'].includes(failure.code)) return '服务器暂时无法连接图片 API，请稍后重试'
  if (failure.code === 'API_TIMEOUT') return '图片生成超时，请稍后重试'
  if (/rate limit|too many requests|429|quota/i.test(message)) return '图片生成请求较多，请稍后再试'
  if (/abort|aborted/i.test(message)) return '图片生成已停止'
  if (/参考图|reference/i.test(message)) return '人格参考图暂时无法使用，请检查图片格式后重试'
  return '图片生成失败，请稍后重试'
}

// ── Quota check ───────────────────────────────────────────────────────────────

async function checkAndIncrementQuota(userId) {
  await pool.query(
    `UPDATE users SET quota_used=0,quota_reset_at=now()
     WHERE id=$1 AND quota_reset_at::date<>current_date`,
    [userId]
  )
  const { rows: updatedRows } = await pool.query(
    `UPDATE users SET quota_used=quota_used+1
     WHERE id=$1 AND quota_used<daily_quota RETURNING quota_used,daily_quota`,
    [userId]
  )
  if (!updatedRows[0]) {
    const [{ rows }, { rows: settingRows }] = await Promise.all([
      pool.query(`SELECT quota_used,daily_quota FROM users WHERE id=$1`, [userId]),
      pool.query(`SELECT value FROM system_settings WHERE key='quota_exhausted_message'`),
    ])
    return {
      allowed: false,
      quotaUsed: rows[0]?.quota_used || 0,
      dailyQuota: rows[0]?.daily_quota || 0,
      message: String(settingRows[0]?.value || '今日消息已用完喵，明天再来吧～'),
    }
  }
  return {
    allowed: true,
    quotaUsed: updatedRows[0].quota_used,
    dailyQuota: updatedRows[0].daily_quota,
  }
}

// ── Memory retrieval (embeddings with lexical fallback) ───────────────────────

export async function getRelevantMemories(userId, query) {
  const queryEmbedding = await createEmbedding(query)
  const { rows } = await pool.query(
    `SELECT id,content,summary,embedding FROM memories WHERE user_id=$1 AND is_active=true`,
    [userId]
  )
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean)
  return rows
    .map(memory => ({
      ...memory,
      relevance: queryEmbedding && memory.embedding
        ? cosineSimilarity(queryEmbedding, memory.embedding)
        : terms.filter(term => memory.content.toLowerCase().includes(term)).length / Math.max(terms.length, 1),
    }))
    .filter(memory => memory.relevance > 0)
    .sort((left, right) => right.relevance - left.relevance)
    .slice(0, 5)
}

// ── Chat route ────────────────────────────────────────────────────────────────

export default async function chatRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }

  // Send message (SSE)
  fastify.post('/conversations/:id/chat', auth, async (req, reply) => {
    const { content, mediaUrls, contextClear, clientId, emojiId } = req.body
    const userId = req.user.sub
    const convId = req.params.id
    const startTime = Date.now()
    if (!String(content || '').trim() && !mediaUrls?.length && !emojiId) {
      return reply.code(400).send({ error: '消息内容不能为空' })
    }

    // Verify conversation ownership
    const { rows: convRows } = await pool.query(
      `SELECT * FROM conversations WHERE id=$1 AND user_id=$2`, [convId, userId]
    )
    const conv = convRows[0]
    if (!conv) return reply.code(404).send({ error: 'Conversation not found' })

    let sentEmoji = null
    if (emojiId) {
      const { rows: emojiRows } = await pool.query(
        `SELECT id,url,emotion_tag,description FROM emojis
         WHERE id::text=$1 AND is_active=true`,
        [String(emojiId)]
      )
      sentEmoji = emojiRows[0] || null
      if (!sentEmoji) return reply.code(400).send({ error: '表情包不存在或已停用' })
    }
    const modelContent = sentEmoji ? buildEmojiUserContent(sentEmoji) : String(content || '')
    const modelMediaUrls = sentEmoji ? [] : (Array.isArray(mediaUrls) ? mediaUrls : [])
    const messagePreview = sentEmoji ? buildEmojiPreview(sentEmoji) : modelContent.slice(0, 256)

    const { rows: modelConfigRows } = await pool.query(
      `SELECT is_enabled,supports_vision FROM model_configs WHERE model_id=$1`,
      [conv.model_id]
    )
    if (!modelConfigRows[0] || modelConfigRows[0].is_enabled === false) {
      return reply.code(503).send({ error: 'This model is currently disabled' })
    }
    if (modelMediaUrls.length && !modelConfigRows[0].supports_vision) {
      return reply.code(400).send({ error: '当前模型不支持图片，请切换到支持视觉的模型' })
    }

    // Check quota only after the conversation has been validated.
    const quota = await checkAndIncrementQuota(userId)
    if (!quota.allowed) {
      return reply.code(429).send({
        error: quota.message,
        quotaUsed: quota.quotaUsed,
        dailyQuota: quota.dailyQuota,
      })
    }

    // Fetch user info for logging
    const { rows: userRows } = await pool.query(
      `SELECT username, email FROM users WHERE id=$1`, [userId]
    )
    const logUser = userRows[0] || {}

    // Get persona system prompt
    let systemPrompt = '你是一只名叫"喵喵"的猫娘，活泼可爱。请在回复末尾加上<!--{"emotion":"happy","action":null}-->'
    let personaReferenceImageUrls = []
    if (conv.persona_id) {
      const { rows: pRows } = await pool.query(
        `SELECT system_prompt,reference_image_urls FROM personas WHERE id=$1`,
        [conv.persona_id]
      )
      if (pRows[0]) {
        systemPrompt = pRows[0].system_prompt
        personaReferenceImageUrls = pRows[0].reference_image_urls || []
      }
    }
    systemPrompt = ensurePersonaEmotionPrompt(systemPrompt)

    // Get relevant memories
    const memories = await getRelevantMemories(userId, modelContent)

    // Build context messages
    let contextMessages = []
    let contextTrimmed = false
    if (!contextClear) {
      const contextLimit = Math.max(2, (conv.context_turns || 20) * 2)
      const { rows: history } = await pool.query(
        `SELECT role, content FROM messages
         WHERE conversation_id=$1
           AND NOT (role='assistant' AND content_type='emoji')
         ORDER BY created_at DESC LIMIT $2`,
        [convId, contextLimit + 1]
      )
      contextTrimmed = history.length > contextLimit
      contextMessages = history.slice(0, contextLimit).reverse()
    }

    // Save user message
    const { rows: userMsgRows } = await pool.query(
      `INSERT INTO messages(conversation_id,role,content,content_type,media_urls,emoji_id,client_id)
       VALUES($1,'user',$2,$3,$4,$5,$6)
       ON CONFLICT(conversation_id,client_id) WHERE client_id IS NOT NULL
       DO UPDATE SET content=EXCLUDED.content,
                     content_type=EXCLUDED.content_type,
                     media_urls=EXCLUDED.media_urls,
                     emoji_id=EXCLUDED.emoji_id
       RETURNING id`,
      [
        convId,
        modelContent,
        sentEmoji ? 'emoji' : (modelMediaUrls.length ? 'image' : 'text'),
        sentEmoji ? [sentEmoji.url] : (modelMediaUrls.length ? modelMediaUrls : null),
        sentEmoji?.id || null,
        clientId || null,
      ]
    )
    await pool.query(
      `UPDATE conversations SET last_message_preview=$1,last_message_at=now(),updated_at=now() WHERE id=$2`,
      [messagePreview, convId]
    )
    if (!sentEmoji) queueAutoMemory({ userId, content: modelContent })

    // SSE headers
    reply.hijack()
    reply.raw.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    })

    const send = (data) => {
      if (reply.raw.destroyed || reply.raw.writableEnded) return
      reply.raw.write(`data: ${JSON.stringify(data)}\n\n`)
    }
    const abortController = new AbortController()
    const heartbeatTimer = setInterval(() => send({ type: 'heartbeat' }), 15_000)
    const handleDisconnect = () => {
      clearInterval(heartbeatTimer)
      if (!reply.raw.writableEnded) abortController.abort()
    }
    reply.raw.on('close', handleDisconnect)

    // Emit thinking_start
    send({ type: 'message_ack', client_id: clientId || null, message_id: userMsgRows[0].id })
    send({ type: 'thinking_start' })
    send({ type: 'quota', quota_used: quota.quotaUsed, daily_quota: quota.dailyQuota })
    if (contextClear) send({ type: 'context_cleared', message: '已清空早期对话上下文' })
    else if (contextTrimmed) send({ type: 'context_trimmed', message: '已自动清理早期对话' })

    // Emit memories
    if (memories.length > 0) {
      send({ type: 'memory', count: memories.length, items: memories.map(m => ({ id: m.id, summary: m.summary || m.content.slice(0, 30) })) })
    }

    // Build final messages array for AI
    const memoryBlock = memories.length > 0
      ? `\n\n[主人的相关记忆]\n${memories.map(m => `- ${m.content}`).join('\n')}`
      : ''

    let imageConfigError = null
    const imageConfig = await getImageGenerationConfig().catch((error) => {
      imageConfigError = error
      return { enabled: false, configured: false, ready: false, model: 'gpt-image-2' }
    })
    const imageCapabilityBlock = imageConfig.ready
      ? `\n\n[应用能力] 本应用可在用户确实需要图片时自动生成并随后发送图片。遇到明确的画图或生图请求，请自然回应正在准备画面，不要声称自己无法生成图片；必须按末尾 JSON 协议设置 generateImage=true 并提供完善后的 imagePrompt，无需在正文中输出生图提示词或技术细节。当前人格有 ${personaReferenceImageUrls.length} 张视觉参考图，生成链路会自动使用这些参考图。若存在参考图且画面涉及该人格，imagePrompt 要求保持参考图中的身份、五官、发型、标志性服装、身体比例及整体画风一致。`
      : ''
    const aiMessages = [
      { role: 'system', content: systemPrompt + memoryBlock + imageCapabilityBlock },
      ...contextMessages.map(m => ({ role: m.role, content: m.content })),
      { role: 'user', content: modelContent, mediaUrls: modelMediaUrls },
    ]

    let fullResponse = ''
    let finalUsage = { inputTokens: 0, outputTokens: 0 }
    let chatStatus = 'ok'
    let chatError = null
    let apiKeyId = null

    const saveAssistantReply = async ({ text, emotion, action }) => {
      const normalizedText = String(text || '').trim()
      if (!normalizedText) return
      await pool.query(
        `INSERT INTO messages(
           conversation_id,role,content,emotion,action_text,reply_to_client_id
         )
         VALUES($1,'assistant',$2,$3,$4,$5)`,
        [convId, normalizedText, emotion, action, clientId || null]
      )
      await pool.query(
        `UPDATE conversations SET last_message_preview=$1,last_message_at=now(),updated_at=now() WHERE id=$2`,
        [normalizedText.slice(0, 256), convId]
      )
    }

    const saveAssistantEmoji = async (emoji) => {
      if (!emoji) return null
      const client = await pool.connect()
      try {
        await client.query('BEGIN')
        const { rows } = await client.query(
          `SELECT id,url,emotion_tag,description FROM emojis WHERE id=$1 AND is_active=true FOR UPDATE`,
          [emoji.id]
        )
        const current = rows[0]
        if (!current) {
          await client.query('ROLLBACK')
          return null
        }
        const emojiContent = buildEmojiAssistantContent(current)
        await client.query(
          `INSERT INTO messages(
             conversation_id,role,content,content_type,media_urls,emoji_id,reply_to_client_id
           )
           VALUES($1,'assistant',$2,'emoji',$3,$4,$5)`,
          [convId, emojiContent, [current.url], current.id, clientId || null]
        )
        await client.query(`UPDATE emojis SET send_count=send_count+1 WHERE id=$1`, [current.id])
        await client.query('COMMIT')
        return current
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {})
        req.log.warn({ err: error }, 'Failed to persist the selected AI emoji')
        return null
      } finally {
        client.release()
      }
    }

    const addUsage = (left, right) => ({
      inputTokens: (left?.inputTokens || 0) + (right?.inputTokens || 0),
      outputTokens: (left?.outputTokens || 0) + (right?.outputTokens || 0),
    })

    const pickEmoji = async ({ userContent, assistantText, replyEmotion }) => {
      let decisionUsage = { inputTokens: 0, outputTokens: 0 }
      const decide = async (messages, maxTokens = 80) => {
        const decisionController = new AbortController()
        const abortDecision = () => decisionController.abort(abortController.signal.reason)
        if (abortController.signal.aborted) abortDecision()
        else abortController.signal.addEventListener('abort', abortDecision, { once: true })
        const timeout = setTimeout(
          () => decisionController.abort(new Error('Emoji decision timed out')),
          12_000
        )
        timeout.unref?.()
        try {
          const result = await completeAI({
            provider: conv.model_provider,
            modelId: conv.model_id,
            messages,
            temperature: 0.1,
            maxTokens,
            signal: decisionController.signal,
          })
          decisionUsage = addUsage(decisionUsage, result.usage)
          return result.text
        } finally {
          clearTimeout(timeout)
          abortController.signal.removeEventListener('abort', abortDecision)
        }
      }

      try {
        const { rows: tagRows } = await pool.query(
          `SELECT DISTINCT emotion_tag FROM emojis WHERE is_active=true ORDER BY emotion_tag`
        )
        const tags = tagRows.map(row => row.emotion_tag).filter(Boolean)
        if (!tags.length) return { emoji: null, usage: decisionUsage }
        const sendDecision = await decide(buildEmojiSendDecisionMessages({
          userContent,
          assistantText,
          replyEmotion,
        }))
        if (parseEmojiSendDecision(sendDecision) !== true) {
          return { emoji: null, usage: decisionUsage }
        }

        const tagDecision = await decide(buildEmojiTagDecisionMessages({
          userContent,
          assistantText,
          replyEmotion,
          tags,
        }))
        const selectedTag = parseEmojiTagDecision(tagDecision, tags)
        if (!selectedTag) return { emoji: null, usage: decisionUsage }

        const { rows: emojis } = await pool.query(
          `SELECT id,url,filename,emotion_tag,description,scene_keywords
           FROM emojis WHERE is_active=true AND emotion_tag=$1
           ORDER BY created_at,id`,
          [selectedTag]
        )
        if (!emojis.length) return { emoji: null, usage: decisionUsage }
        const numberDecision = await decide(buildEmojiCandidateDecisionMessages({
          userContent,
          assistantText,
          tag: selectedTag,
          emojis,
        }))
        const selectedNumber = parseEmojiNumberDecision(numberDecision, emojis.length)
        if (!selectedNumber) return { emoji: null, usage: decisionUsage }

        return { emoji: emojis[selectedNumber - 1], usage: decisionUsage }
      } catch (error) {
        if (abortController.signal.aborted) throw error
        req.log.warn({ err: error }, 'AI emoji selection failed; continuing without an emoji')
        return { emoji: null, usage: decisionUsage }
      }
    }

    const sendDone = ({ emotion, action, emojiId = null, usage = finalUsage }) => {
      send({
        type: 'done',
        emotion,
        action,
        emoji_id: emojiId,
        usage: { input_tokens: usage.inputTokens, output_tokens: usage.outputTokens },
      })
    }

    const handleUnavailableImageService = async () => {
      const message = imageConfig.enabled === false
        ? '图片生成功能暂时未启用，请稍后再试喵～'
        : 'Image 2.0 暂时未配置好，请联系管理员后再试喵～'
      chatStatus = 'error'
      chatError = imageConfigError?.message || message
      fullResponse = message
      send({ type: 'image_generation_start', message: '正在检查 Image 2.0…' })
      send({ type: 'image_generation_error', message })
      await saveAssistantReply({ text: message, emotion: 'sad', action: null })
      send({ type: 'delta', content: message })
      sendDone({ emotion: 'sad', action: null })
    }

    const runImageFlow = async (imagePlan) => {
      send({ type: 'image_generation_start', message: '正在调用 Image 2.0 为你绘制图片…' })
      const imageStartTime = Date.now()
      let progressIndex = 0
      const progressMessages = ['正在完善构图…', '正在绘制画面细节…', '正在进行最后润色…']
      const progressTimer = setInterval(() => {
        send({
          type: 'image_generation_progress',
          message: progressMessages[Math.min(progressIndex, progressMessages.length - 1)],
        })
        progressIndex += 1
      }, 12_000)

      let generated
      try {
        generated = await generatePersonaImage({
          prompt: imagePlan.prompt,
          referenceImageUrls: personaReferenceImageUrls,
          aspectRatio: imagePlan.aspectRatio,
          signal: abortController.signal,
        })
      } catch (imageError) {
        clearInterval(progressTimer)
        logRequest({
          userId,
          username: logUser.username,
          userEmail: logUser.email,
          conversationId: convId,
          conversationTitle: conv.title,
          modelProvider: 'gpt-image',
          modelId: imageConfig.model || 'gpt-image-2',
          requestContent: imagePlan.prompt,
          responseContent: '',
          inputTokens: 0,
          outputTokens: 0,
          durationMs: Date.now() - imageStartTime,
          status: abortController.signal.aborted ? 'cancelled' : 'error',
          errorMsg: String(imageError?.message || imageError),
          apiKeyId: null,
        })
        if (abortController.signal.aborted) {
          chatStatus = 'cancelled'
          chatError = 'client disconnected'
          return
        }
        const publicMessage = publicImageError(imageError)
        const replyText = `${publicMessage}，这次没有让普通聊天模型用文字代替图片。`
        chatStatus = 'error'
        chatError = String(imageError?.message || imageError)
        fullResponse = replyText
        send({ type: 'image_generation_error', message: publicMessage })
        await saveAssistantReply({ text: replyText, emotion: 'sad', action: null })
        send({ type: 'delta', content: replyText })
        sendDone({ emotion: 'sad', action: null })
        return
      } finally {
        clearInterval(progressTimer)
      }

      const caption = imagePlan.caption || '由 Image 2.0 生成的图片'
      const { rows: imageRows } = await pool.query(
        `INSERT INTO messages(
           conversation_id,role,content,content_type,media_urls,emotion,reply_to_client_id
         )
         VALUES($1,'assistant',$2,'image',$3,'excited',$4) RETURNING id,created_at`,
        [convId, caption, [generated.url], clientId || null]
      )
      const imageMessage = imageRows[0]
      await pool.query(
        `UPDATE conversations SET last_message_preview=$1,last_message_at=now(),updated_at=now() WHERE id=$2`,
        [`[图片] ${caption}`.slice(0, 256), convId]
      )
      send({
        type: 'image_generated',
        message: {
          id: imageMessage.id,
          conversationId: convId,
          role: 'assistant',
          content: caption,
          contentType: 'image',
          mediaUrls: [generated.url],
          emotion: 'excited',
          actionText: null,
          source: 'app',
          isError: false,
          isRecalled: false,
          createdAt: imageMessage.created_at?.toISOString?.() || imageMessage.created_at,
        },
      })
      logRequest({
        userId,
        username: logUser.username,
        userEmail: logUser.email,
        conversationId: convId,
        conversationTitle: conv.title,
        modelProvider: 'gpt-image',
        modelId: generated.model,
        requestContent: imagePlan.prompt,
        responseContent: generated.url,
        inputTokens: generated.usage?.input_tokens || 0,
        outputTokens: generated.usage?.output_tokens || 0,
        durationMs: Date.now() - imageStartTime,
        status: 'ok',
        errorMsg: null,
        apiKeyId: generated.apiKeyId,
      })

      let replyResult = null
      try {
        replyResult = await completeAI({
          provider: conv.model_provider,
          modelId: conv.model_id,
          messages: [
            {
              role: 'system',
              content: postImageReplySystemPrompt({
                systemPrompt: systemPrompt + memoryBlock,
                generatedModel: 'Image 2.0',
              }),
            },
            ...contextMessages.map(message => ({ role: message.role, content: message.content })),
            { role: 'user', content: modelContent, mediaUrls: modelMediaUrls },
          ],
          temperature: conv.temperature ?? 0.8,
          maxTokens: Math.min(conv.max_tokens || 4096, 800),
          signal: abortController.signal,
        })
      } catch (replyError) {
        if (abortController.signal.aborted) {
          chatStatus = 'cancelled'
          chatError = 'client disconnected'
          return
        }
      }

      const normalizedReply = normalizePostImageReply(replyResult?.text || '')
      fullResponse = replyResult?.text || normalizedReply.cleanText
      finalUsage = replyResult?.usage || finalUsage
      apiKeyId = replyResult?.apiKeyId || apiKeyId
      await saveAssistantReply({
        text: normalizedReply.cleanText,
        emotion: normalizedReply.emotion,
        action: normalizedReply.action,
      })
      const emojiResult = await pickEmoji({
        userContent: modelContent,
        assistantText: normalizedReply.cleanText,
        replyEmotion: normalizedReply.emotion,
      })
      const emojiData = await saveAssistantEmoji(emojiResult.emoji)
      finalUsage = addUsage(finalUsage, emojiResult.usage)
      send({ type: 'delta', content: normalizedReply.cleanText })
      if (emojiData) send({ type: 'emoji', id: emojiData.id, url: emojiData.url })
      sendDone({
        emotion: normalizedReply.emotion,
        action: normalizedReply.action,
        emojiId: emojiData?.id || null,
        usage: finalUsage,
      })
    }

    const runRegularChat = async () => {
      const deltaFilter = createPersonaDeltaFilter(contentDelta => {
        send({ type: 'delta', content: contentDelta })
      })
      await streamAI({
        provider: conv.model_provider,
        modelId: conv.model_id,
        messages: aiMessages,
        temperature: conv.temperature ?? 0.8,
        maxTokens: conv.max_tokens || 4096,
        signal: abortController.signal,
        onCredential: id => { apiKeyId = id },
        onDelta: (delta) => {
          fullResponse += delta
          deltaFilter.push(delta)
        },
        onDone: async (text, usage) => {
          deltaFilter.finish()
          fullResponse = text
          finalUsage = usage
          const { emotion, action, cleanText } = extractMeta(text)
          await saveAssistantReply({ text: cleanText, emotion, action })
          const emojiResult = await pickEmoji({
            userContent: modelContent,
            assistantText: cleanText,
            replyEmotion: emotion,
          })
          const emojiData = await saveAssistantEmoji(emojiResult.emoji)
          finalUsage = addUsage(usage, emojiResult.usage)
          if (emojiData) send({ type: 'emoji', id: emojiData.id, url: emojiData.url })
          sendDone({ emotion, action, emojiId: emojiData?.id || null, usage: finalUsage })
        },
      })
    }

    const scheduleConversationTitle = () => {
      if (conv.title !== '新会话') return
      setImmediate(async () => {
        try {
          const credentials = await getProviderKey(conv.model_provider)
          if (!credentials?.apiKey) return
          const { apiKey, baseUrl: configuredBaseUrl } = credentials
          const titlePrompt = `请用5字以内概括这个开场消息，只返回标题文字，不加引号：${modelContent}`
          let generatedTitle = ''

          if (conv.model_provider === 'anthropic') {
            const client = new Anthropic({
              apiKey,
              baseURL: normalizeProviderBaseUrl(conv.model_provider, configuredBaseUrl),
              timeout: providerRequestTimeoutMs(),
              maxRetries: 1,
              fetch: providerFetch,
            })
            const message = await client.messages.create({
              model: conv.model_id,
              max_tokens: 32,
              messages: [{ role: 'user', content: titlePrompt }],
            })
            generatedTitle = message.content[0]?.text?.trim() || ''
          } else {
            generatedTitle = await withProviderBaseUrlFallback(
              conv.model_provider,
              configuredBaseUrl,
              async baseURL => {
                const client = new OpenAI({
                  apiKey,
                  baseURL,
                  timeout: providerRequestTimeoutMs(),
                  maxRetries: 1,
                  fetch: providerFetch,
                })
                const response = await client.chat.completions.create({
                  model: conv.model_id,
                  max_tokens: 32,
                  messages: [{ role: 'user', content: titlePrompt }],
                  stream: false,
                })
                if (!response || typeof response !== 'object' || !Array.isArray(response.choices)) {
                  throw invalidProviderResponse('标题生成接口返回了非兼容的 JSON 数据')
                }
                return response.choices[0]?.message?.content?.trim() || ''
              }
            )
          }

          if (generatedTitle) {
            await pool.query(
              `UPDATE conversations SET title=$1, updated_at=now() WHERE id=$2 AND title='新会话'`,
              [generatedTitle.slice(0, 20), convId]
            )
          }
        } catch {
          // Best-effort title generation must never affect the chat response.
        }
      })
    }

    try {
      const explicitImageRequest = !sentEmoji && isExplicitImageGenerationRequest(modelContent)
      if (explicitImageRequest) {
        if (!imageConfig.ready) {
          await handleUnavailableImageService()
        } else {
          await runImageFlow(buildDirectImagePlan({
            userText: modelContent,
            personaPrompt: systemPrompt,
            referenceImageCount: personaReferenceImageUrls.length,
          }))
        }
      } else {
        let plannedImage = null
        if (!sentEmoji && imageConfig.ready && shouldRunImageGenerationPlanner(modelContent)) {
          try {
            const decision = await completeAI({
              provider: conv.model_provider,
              modelId: conv.model_id,
              messages: [
                {
                  role: 'system',
                  content: buildImagePlannerPrompt({
                    personaPrompt: systemPrompt,
                    referenceImageCount: personaReferenceImageUrls.length,
                  }),
                },
                ...contextMessages.slice(-6).map(message => ({
                  role: message.role,
                  content: message.content,
                })),
                { role: 'user', content: modelContent },
              ],
              temperature: 0.1,
              maxTokens: 1200,
              signal: abortController.signal,
            })
            plannedImage = parseImagePlan(decision.text)
          } catch {
            // A planner failure falls back to the normal chat path.
          }
        }
        if (plannedImage) await runImageFlow(plannedImage)
        else await runRegularChat()
      }
      if (!abortController.signal.aborted) scheduleConversationTitle()
    } catch (error) {
      if (!abortController.signal.aborted) {
        chatStatus = 'error'
        chatError = error.message
        send({ type: 'error', message: publicChatError(error) })
        sendDone({ emotion: 'sad', action: null, usage: { inputTokens: 0, outputTokens: 0 } })
      } else {
        chatStatus = 'cancelled'
        chatError = 'client disconnected'
      }
    }
    reply.raw.off('close', handleDisconnect)
    clearInterval(heartbeatTimer)
    if (!reply.raw.destroyed && !reply.raw.writableEnded) {
      reply.raw.write('data: [DONE]\n\n')
      reply.raw.end()
    }

    // Log the request (fire-and-forget)
    logRequest({
      userId,
      username: logUser.username,
      userEmail: logUser.email,
      conversationId: convId,
      conversationTitle: conv.title,
      modelProvider: conv.model_provider,
      modelId: conv.model_id,
      requestContent: modelContent,
      responseContent: fullResponse,
      inputTokens: finalUsage.inputTokens,
      outputTokens: finalUsage.outputTokens,
      durationMs: Date.now() - startTime,
      status: chatStatus,
      errorMsg: chatError,
      apiKeyId,
    })
  })

  fastify.post('/conversations/:id/messages/:msgId/regenerate', auth, async (req, reply) => {
    const userId = req.user.sub
    const convId = req.params.id
    const { rows: targetRows } = await pool.query(
      `SELECT m.created_at AS message_created_at,
              m.reply_to_client_id AS message_reply_to_client_id,c.*
       FROM messages m JOIN conversations c ON c.id=m.conversation_id
       WHERE m.id=$1 AND m.conversation_id=$2 AND m.role='assistant'
         AND m.content_type='text' AND c.user_id=$3`,
      [req.params.msgId, convId, userId]
    )
    const conv = targetRows[0]
    if (!conv) return reply.code(404).send({ error: 'Message not found' })

    const { rows: modelRows } = await pool.query(`SELECT is_enabled FROM model_configs WHERE model_id=$1`, [conv.model_id])
    if (modelRows[0]?.is_enabled === false) return reply.code(503).send({ error: 'This model is currently disabled' })
    const quota = await checkAndIncrementQuota(userId)
    if (!quota.allowed) return reply.code(429).send({ error: quota.message })

    let systemPrompt = '你是一只名叫“喵喵”的猫娘，活泼可爱、温柔陪伴。'
    if (conv.persona_id) {
      const { rows } = await pool.query(`SELECT system_prompt FROM personas WHERE id=$1`, [conv.persona_id])
      if (rows[0]) systemPrompt = rows[0].system_prompt
    }
    systemPrompt = ensurePersonaEmotionPrompt(systemPrompt)
    const { rows: history } = await pool.query(
      `SELECT role,content,content_type,media_urls FROM messages
       WHERE conversation_id=$1 AND created_at<$2
         AND NOT (role='assistant' AND content_type='emoji')
       ORDER BY created_at ASC`,
      [convId, conv.message_created_at]
    )
    const latestUserMessage = [...history].reverse().find(message => message.role === 'user')
    if (latestUserMessage?.content && latestUserMessage.content_type !== 'emoji') {
      queueAutoMemory({ userId, content: latestUserMessage.content })
    }
    const messages = [
      { role: 'system', content: systemPrompt },
      ...history.map(toModelHistoryMessage),
    ]

    reply.hijack()
    reply.raw.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    const send = data => reply.raw.write(`data: ${JSON.stringify(data)}\n\n`)
    const abortController = new AbortController()
    const handleDisconnect = () => {
      if (!reply.raw.writableEnded) abortController.abort()
    }
    reply.raw.on('close', handleDisconnect)
    send({ type: 'thinking_start' })
    send({ type: 'quota', quota_used: quota.quotaUsed, daily_quota: quota.dailyQuota })

    try {
      await streamAI({
        provider: conv.model_provider,
        modelId: conv.model_id,
        messages,
        temperature: conv.temperature ?? 0.8,
        maxTokens: conv.max_tokens || 4096,
        signal: abortController.signal,
        onDelta: content => send({ type: 'delta', content }),
        onDone: async (text, usage) => {
          const { emotion, action, cleanText } = extractMeta(text)
          await pool.query(
            `UPDATE messages SET content=$1,emotion=$2,action_text=$3,is_error=false,created_at=now()
             WHERE id=$4`,
            [cleanText, emotion, action, req.params.msgId]
          )
          if (conv.message_reply_to_client_id) {
            await pool.query(
              `DELETE FROM messages
               WHERE conversation_id=$1 AND role='assistant' AND content_type='emoji'
                 AND reply_to_client_id=$2`,
              [convId, conv.message_reply_to_client_id]
            )
          }
          await pool.query(
            `UPDATE conversations SET last_message_preview=$1,last_message_at=now(),updated_at=now() WHERE id=$2`,
            [cleanText.slice(0, 256), convId]
          )
          send({
            type: 'done', emotion, action, emoji_id: null,
            usage: { input_tokens: usage.inputTokens, output_tokens: usage.outputTokens },
          })
        },
      })
    } catch (error) {
      if (!abortController.signal.aborted) send({ type: 'error', message: publicChatError(error) })
    }
    reply.raw.off('close', handleDisconnect)
    if (!reply.raw.destroyed && !reply.raw.writableEnded) {
      reply.raw.write('data: [DONE]\n\n')
      reply.raw.end()
    }
  })

  // Delete message
  fastify.delete('/conversations/:id/messages/:msgId', auth, async (req, reply) => {
    const result = await pool.query(
      `WITH target AS (
         SELECT message.id,message.conversation_id,message.role,message.reply_to_client_id
         FROM messages message
         JOIN conversations conversation ON conversation.id=message.conversation_id
         WHERE (message.id=$1 OR message.client_id=$1)
           AND message.conversation_id=$2 AND conversation.user_id=$3
         LIMIT 1
       )
       DELETE FROM messages message USING target
       WHERE message.id=target.id
          OR (target.role='assistant'
              AND target.reply_to_client_id IS NOT NULL
              AND message.conversation_id=target.conversation_id
              AND message.role='assistant' AND message.content_type='emoji'
              AND message.reply_to_client_id=target.reply_to_client_id)`,
      [req.params.msgId, req.params.id, req.user.sub]
    )
    if (result.rowCount === 0) return reply.code(404).send({ error: 'Message not found' })
    return { success: true }
  })

  fastify.post('/conversations/:id/messages/:msgId/memory', auth, async (req, reply) => {
    const { rows } = await pool.query(
      `SELECT m.content FROM messages m JOIN conversations c ON c.id=m.conversation_id
       WHERE m.id=$1 AND m.conversation_id=$2 AND c.user_id=$3`,
      [req.params.msgId, req.params.id, req.user.sub]
    )
    if (!rows[0]?.content) return reply.code(404).send({ error: 'Message not found' })
    const content = rows[0].content
    const embedding = await createEmbedding(content)
    const { rows: memoryRows } = await pool.query(
      `INSERT INTO memories(user_id,content,summary,source,embedding)
       VALUES($1,$2,$3,'message',$4) RETURNING id,content,summary,source,created_at`,
      [req.user.sub, content, content.slice(0, 30), embedding ? JSON.stringify(embedding) : null]
    )
    return memoryRows[0]
  })
}
