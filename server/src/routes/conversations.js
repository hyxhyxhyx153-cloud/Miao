import { pool } from '../db/client.js'
import { buildEmojiUserContent } from '../services/emojiContext.js'

export default async function conversationRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }

  // List
  fastify.get('/conversations', auth, async (req) => {
    const search = String(req.query.search || '').trim()
    const { rows } = await pool.query(
      `SELECT c.* FROM conversations c
       WHERE c.user_id=$1 AND (
         $2::text='' OR c.title ILIKE '%'||$2||'%' OR EXISTS(
           SELECT 1 FROM messages m WHERE m.conversation_id=c.id AND m.content ILIKE '%'||$2||'%'
         )
       )
       ORDER BY c.is_pinned DESC,c.last_message_at DESC`,
      [req.user.sub, search]
    )
    return rows.map(convToDto)
  })

  // Create
  fastify.post('/conversations', auth, async (req, reply) => {
    const {
      modelProvider = 'anthropic', modelId, personaId, title,
      temperature = 0.8, maxTokens = 4096, contextTurns = 20,
    } = req.body
    const selectedModel = modelId || 'claude-haiku-4-5-20251001'
    const { rows: modelRows } = await pool.query(
      `SELECT provider,is_enabled FROM model_configs WHERE model_id=$1`,
      [selectedModel]
    )
    if (!modelRows[0] || modelRows[0].is_enabled === false) {
      return reply.code(400).send({ error: '该模型不存在或当前不可用' })
    }
    if (modelRows[0].provider !== modelProvider) {
      return reply.code(400).send({ error: '模型与提供商不匹配' })
    }
    if (personaId) {
      const { rows: personaRows } = await pool.query(
        `SELECT id FROM personas WHERE id=$1 AND (is_builtin=true OR user_id=$2)`,
        [personaId, req.user.sub]
      )
      if (!personaRows[0]) return reply.code(400).send({ error: '人格不存在或不可用' })
    }
    const parsedTemperature = Number(temperature)
    const parsedMaxTokens = Number(maxTokens)
    const parsedContextTurns = Number(contextTurns)
    if (![parsedTemperature, parsedMaxTokens, parsedContextTurns].every(Number.isFinite)) {
      return reply.code(400).send({ error: '会话参数格式不正确' })
    }
    const { rows } = await pool.query(
      `INSERT INTO conversations(user_id,title,model_provider,model_id,persona_id,temperature,max_tokens,context_turns)
       VALUES($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *`,
      [
        req.user.sub, title || '新会话', modelProvider, selectedModel, personaId || null,
        Math.max(0, Math.min(parsedTemperature, 2)),
        Math.max(1, Math.min(Math.trunc(parsedMaxTokens), 32768)),
        Math.max(1, Math.min(Math.trunc(parsedContextTurns), 100)),
      ]
    )
    return convToDto(rows[0])
  })

  // Update
  fastify.patch('/conversations/:id', auth, async (req, reply) => {
    const body = req.body || {}
    const { title, isPinned, isRead, modelId } = body
    const userAvatar = validateAvatarUpdate(body, 'userAvatarUrl')
    const aiAvatar = validateAvatarUpdate(body, 'aiAvatarUrl')
    if (!userAvatar.valid || !aiAvatar.valid) {
      return reply.code(400).send({ error: '头像地址必须是有效的 HTTP/HTTPS URL' })
    }
    let selectedModelId = null
    let selectedProvider = null
    if (modelId !== undefined) {
      selectedModelId = String(modelId).trim()
      if (!selectedModelId) return reply.code(400).send({ error: '模型不能为空' })
      const { rows: modelRows } = await pool.query(
        `SELECT provider,is_enabled FROM model_configs WHERE model_id=$1`,
        [selectedModelId]
      )
      if (!modelRows[0] || modelRows[0].is_enabled !== true) {
        return reply.code(400).send({ error: '该模型不存在或当前不可用' })
      }
      selectedProvider = modelRows[0].provider
    }
    const { rows } = await pool.query(
      `UPDATE conversations SET
         title = COALESCE($1, title),
         is_pinned = COALESCE($2, is_pinned),
         unread_count = CASE WHEN $3::boolean=true THEN 0 ELSE unread_count END,
         model_provider = COALESCE($4, model_provider),
         model_id = COALESCE($5, model_id),
         user_avatar_url = CASE WHEN $6::boolean THEN $7 ELSE user_avatar_url END,
         ai_avatar_url = CASE WHEN $8::boolean THEN $9 ELSE ai_avatar_url END,
         updated_at = now()
       WHERE id=$10 AND user_id=$11 RETURNING *`,
      [
        title, isPinned, isRead, selectedProvider, selectedModelId,
        userAvatar.present, userAvatar.value, aiAvatar.present, aiAvatar.value,
        req.params.id, req.user.sub,
      ]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Not found' })
    return convToDto(rows[0])
  })

  // Delete
  fastify.delete('/conversations/:id', auth, async (req, reply) => {
    const res = await pool.query(
      `DELETE FROM conversations WHERE id=$1 AND user_id=$2`,
      [req.params.id, req.user.sub]
    )
    if (res.rowCount === 0) return reply.code(404).send({ error: 'Not found' })
    return { success: true }
  })

  // Messages list
  fastify.get('/conversations/:id/messages', auth, async (req, reply) => {
    const { limit = 100, offset = 0 } = req.query
    const conv = await getConv(req.params.id, req.user.sub)
    if (!conv) return reply.code(404).send({ error: 'Not found' })

    const { rows } = await pool.query(
      `SELECT * FROM messages WHERE conversation_id=$1 ORDER BY created_at ASC LIMIT $2 OFFSET $3`,
      [req.params.id, limit, offset]
    )
    return rows.map(msgToDto)
  })

  fastify.post('/conversations/:id/messages/sync', auth, async (req, reply) => {
    const conv = await getConv(req.params.id, req.user.sub)
    if (!conv) return reply.code(404).send({ error: 'Not found' })
    const messages = Array.isArray(req.body?.messages) ? req.body.messages : []
    if (messages.length > 100) return reply.code(400).send({ error: 'Too many messages' })
    const synced = []
    for (const message of messages) {
      const content = String(message.content || '')
      const mediaUrls = Array.isArray(message.mediaUrls) ? message.mediaUrls : []
      if (!message.clientId || (!content.trim() && !mediaUrls.length && !message.emojiId)) continue
      const { rows: emojiRows } = message.emojiId
        ? await pool.query(
          `SELECT id,url,emotion_tag,description FROM emojis
           WHERE id::text=$1 AND is_active=true`,
          [String(message.emojiId)]
        )
        : { rows: [] }
      const sentEmoji = emojiRows[0] || null
      if (message.emojiId && !sentEmoji) {
        return reply.code(400).send({ error: '表情包不存在或已停用' })
      }
      const storedContent = sentEmoji ? buildEmojiUserContent(sentEmoji) : content
      const storedMediaUrls = sentEmoji ? [sentEmoji.url] : mediaUrls
      const { rows } = await pool.query(
        `INSERT INTO messages(conversation_id,role,content,content_type,media_urls,emoji_id,client_id,source)
         VALUES($1,'user',$2,$3,$4,$5,$6,'app')
         ON CONFLICT(conversation_id,client_id) WHERE client_id IS NOT NULL
         DO UPDATE SET content=EXCLUDED.content,
                       content_type=EXCLUDED.content_type,
                       media_urls=EXCLUDED.media_urls,
                       emoji_id=EXCLUDED.emoji_id
         RETURNING id,client_id`,
        [
          req.params.id,
          storedContent,
          sentEmoji ? 'emoji' : (storedMediaUrls.length ? 'image' : 'text'),
          storedMediaUrls.length ? storedMediaUrls : null,
          sentEmoji?.id || null,
          message.clientId,
        ]
      )
      synced.push({ clientId: rows[0].client_id, serverId: rows[0].id })
    }
    return { synced }
  })

  fastify.post('/conversations/:id/messages/:messageId/recall', auth, async (req, reply) => {
    const { rows } = await pool.query(
      `UPDATE messages m SET content='你撤回了一条消息',content_type='system',is_recalled=true
       FROM conversations c
       WHERE (m.id=$1 OR m.client_id=$1) AND m.conversation_id=$2 AND c.id=m.conversation_id AND c.user_id=$3
         AND m.role='user' AND m.created_at>now()-interval '2 minutes'
       RETURNING m.*`,
      [req.params.messageId, req.params.id, req.user.sub]
    )
    if (!rows[0]) return reply.code(400).send({ error: '消息不存在或已超过2分钟撤回期限' })
    return msgToDto(rows[0])
  })
}

async function getConv(id, userId) {
  const { rows } = await pool.query(
    `SELECT * FROM conversations WHERE id=$1 AND user_id=$2`, [id, userId]
  )
  return rows[0] || null
}

function convToDto(r) {
  return {
    id: r.id,
    title: r.title,
    modelProvider: r.model_provider,
    modelId: r.model_id,
    personaId: r.persona_id,
    userAvatarUrl: r.user_avatar_url,
    aiAvatarUrl: r.ai_avatar_url,
    isPinned: r.is_pinned,
    unreadCount: r.unread_count || 0,
    isWechat: r.is_wechat || false,
    lastMessagePreview: r.last_message_preview || '',
    temperature: r.temperature ?? 0.8,
    maxTokens: r.max_tokens || 4096,
    contextTurns: r.context_turns || 20,
    lastMessageAt: r.last_message_at?.getTime?.()?.toString() || null,
    createdAt: r.created_at?.toISOString(),
  }
}

function validateAvatarUpdate(body, key) {
  if (!Object.prototype.hasOwnProperty.call(body, key)) {
    return { present: false, value: null, valid: true }
  }
  if (body[key] == null || String(body[key]).trim() === '') {
    return { present: true, value: null, valid: true }
  }
  if (typeof body[key] !== 'string' || body[key].length > 1024) {
    return { present: true, value: null, valid: false }
  }
  try {
    const url = new URL(body[key])
    const valid = url.protocol === 'http:' || url.protocol === 'https:'
    return { present: true, value: valid ? url.toString() : null, valid }
  } catch {
    return { present: true, value: null, valid: false }
  }
}

function msgToDto(r) {
  return {
    id: r.id,
    conversationId: r.conversation_id,
    role: r.role,
    content: r.content,
    contentType: r.content_type,
    mediaUrls: r.media_urls,
    emojiId: r.emoji_id,
    emotion: r.emotion,
    actionText: r.action_text,
    source: r.source,
    isError: r.is_error,
    isRecalled: r.is_recalled || false,
    clientId: r.client_id,
    replyToClientId: r.reply_to_client_id,
    createdAt: r.created_at?.toISOString(),
  }
}
