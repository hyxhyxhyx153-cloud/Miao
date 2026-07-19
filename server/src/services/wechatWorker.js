import { pool } from '../db/client.js'
import { decryptSecret } from './secretVault.js'
import {
  getClawbotUpdates,
  notifyClawbotStart,
  resetClawbotChannel,
  sendClawbotMessage,
} from './clawbot.js'
import { extractMeta, getRelevantMemories, streamAI } from '../routes/chat.js'
import crypto from 'node:crypto'
import { queueAutoMemory } from './autoMemory.js'
import { ensurePersonaEmotionPrompt } from './personaPrompt.js'
import { deliverWechatBatch } from './wechatDelivery.js'

const workers = new Map()
const workerTransitions = new Map()
const MAX_PRIMARY_DELIVERY_ATTEMPTS = 5
const MAX_TOTAL_DELIVERY_ATTEMPTS = 8
const WECHAT_DELIVERY_FALLBACK = '这条回复暂时无法完整发送，请在 Miao App 中查看，或再发一条消息重试。'

function serializeWorkerTransition(bindingId, operation) {
  const previous = workerTransitions.get(bindingId) || Promise.resolve()
  const current = previous.catch(() => {}).then(operation)
  workerTransitions.set(bindingId, current)
  return current.finally(() => {
    if (workerTransitions.get(bindingId) === current) workerTransitions.delete(bindingId)
  })
}

export async function ensureWechatConversation(binding) {
  if (binding.conversation_id) return binding.conversation_id
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    // The worker keeps a binding object for its lifetime. Lock and reload the
    // row so a persona changed after worker startup is never overwritten by
    // the first incoming WeChat message.
    const { rows: bindingRows } = await client.query(
      `SELECT id,user_id,persona_id,conversation_id
       FROM wechat_bindings WHERE id=$1 FOR UPDATE`,
      [binding.id]
    )
    const currentBinding = bindingRows[0]
    if (!currentBinding) throw new Error('WeChat binding not found')
    if (currentBinding.conversation_id) {
      await client.query('COMMIT')
      binding.conversation_id = currentBinding.conversation_id
      binding.persona_id = currentBinding.persona_id
      return currentBinding.conversation_id
    }

    const { rows: settingsRows } = await client.query(
      `SELECT settings FROM user_settings WHERE user_id=$1`,
      [currentBinding.user_id]
    )
    const settings = settingsRows[0]?.settings || {}
    const preferredModelId = settings.modelId || 'claude-haiku-4-5-20251001'
    // Resolve the provider from the model registry instead of trusting stale
    // client settings. The preferred/default model is used only by INSERT;
    // the conflict branch intentionally never changes an existing WeChat
    // conversation's model.
    const { rows: modelRows } = await client.query(
      `SELECT model_id,provider FROM model_configs
       WHERE is_enabled=true
       ORDER BY CASE WHEN model_id=$1 THEN 0
                     WHEN model_id='claude-haiku-4-5-20251001' THEN 1
                     ELSE 2 END,
                model_id
       LIMIT 1`,
      [preferredModelId]
    )
    if (!modelRows[0]) throw new Error('No enabled model is available for the WeChat conversation')
    const provider = modelRows[0].provider
    const modelId = modelRows[0].model_id
    const { rows } = await client.query(
      `INSERT INTO conversations(
         user_id,title,model_provider,model_id,persona_id,is_wechat,last_message_preview,
         temperature,max_tokens,context_turns
       )
       VALUES($1,'微信喵喵',$2,$3,$4::uuid,true,'',$5,$6,$7)
       ON CONFLICT(user_id) WHERE is_wechat=true DO UPDATE
         SET persona_id=EXCLUDED.persona_id,updated_at=now()
       RETURNING id`,
      [
        currentBinding.user_id,
        provider,
        modelId,
        currentBinding.persona_id || null,
        Number(settings.temperature ?? 0.8),
        Number(settings.maxTokens ?? 4096),
        Number(settings.contextTurns ?? 20),
      ]
    )
    const conversationId = rows[0].id
    await client.query(
      `UPDATE wechat_bindings SET conversation_id=$1,updated_at=now() WHERE id=$2`,
      [conversationId, currentBinding.id]
    )
    await client.query('COMMIT')
    binding.conversation_id = conversationId
    binding.persona_id = currentBinding.persona_id
    return conversationId
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
}

function messageText(message) {
  return (message.item_list || [])
    .filter(item => item.type === 1 && item.text_item?.text)
    .map(item => item.text_item.text)
    .join('\n')
    .trim()
}

function stableUuid(value) {
  const hex = crypto.createHash('sha256').update(value).digest('hex').slice(0, 32)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function messageClientId(bindingId, message) {
  const identity = message.message_id ?? [
    message.from_user_id || '',
    message.session_id || '',
    message.seq || '',
    message.create_time_ms || '',
    message.context_token || '',
    JSON.stringify(message.item_list || []),
  ].join(':')
  return stableUuid(`${bindingId}:${identity}`)
}

function isExpiredBindingError(error) {
  return error?.clawbotCode === -14 || error?.statusCode === 401 || error?.statusCode === 403
}

async function consumeQuota(userId) {
  await pool.query(
    `UPDATE users SET quota_used=0,quota_reset_at=now()
     WHERE id=$1 AND quota_reset_at::date<>current_date`,
    [userId]
  )
  const { rows } = await pool.query(
    `UPDATE users SET quota_used=quota_used+1
     WHERE id=$1 AND quota_used<daily_quota RETURNING quota_used,daily_quota`,
    [userId]
  )
  return rows[0] || null
}

async function saveWechatAssistant(conversationId, content, {
  emotion = 'gentle', action = null, isError = false, replyToClientId = null,
} = {}) {
  const { rows } = await pool.query(
    `INSERT INTO messages(
       conversation_id,role,content,emotion,action_text,source,is_error,
       reply_to_client_id,wechat_delivery_status
     )
     VALUES($1,'assistant',$2,$3,$4,'wechat',$5,$6,'pending')
     ON CONFLICT(conversation_id,reply_to_client_id)
       WHERE source='wechat' AND role='assistant' AND reply_to_client_id IS NOT NULL
     DO UPDATE SET content=messages.content
     RETURNING *`,
    [conversationId, content, emotion, action, isError, replyToClientId]
  )
  await pool.query(
    `UPDATE conversations SET last_message_preview=$1,last_message_at=now(),updated_at=now() WHERE id=$2`,
    [content.slice(0, 256), conversationId]
  )
  return rows[0]
}

async function findWechatAssistant(conversationId, replyToClientId) {
  const { rows } = await pool.query(
    `SELECT * FROM messages
     WHERE conversation_id=$1 AND role='assistant' AND source='wechat'
       AND reply_to_client_id=$2
     LIMIT 1`,
    [conversationId, replyToClientId]
  )
  return rows[0] || null
}

async function deliverWechatAssistant(binding, message, assistant, signal) {
  if (['sent', 'fallback_sent', 'failed'].includes(assistant.wechat_delivery_status) ||
      assistant.wechat_delivered_at) return assistant
  const fallbackDelivery = assistant.wechat_delivery_status === 'fallback_pending'
  const { rows: attemptRows } = await pool.query(
    `UPDATE messages
     SET wechat_delivery_attempts=wechat_delivery_attempts+1,wechat_delivery_error=NULL
     WHERE id=$1 RETURNING *`,
    [assistant.id]
  )
  assistant = attemptRows[0] || assistant
  try {
    await sendClawbotMessage({
      baseUrl: binding.base_url,
      botToken: decryptSecret(binding.bot_token),
      toUserId: message.from_user_id,
      contextToken: message.context_token,
      clientId: fallbackDelivery ? stableUuid(`${assistant.id}:fallback`) : assistant.id,
      text: fallbackDelivery ? WECHAT_DELIVERY_FALLBACK : assistant.content,
      signal,
    })
  } catch (error) {
    const deliveryError = String(error.message || error).slice(0, 1000)
    if (signal?.aborted) {
      await pool.query(
        `UPDATE messages SET wechat_delivery_error=$1 WHERE id=$2`,
        [deliveryError, assistant.id]
      )
      throw error
    }
    if (isExpiredBindingError(error)) {
      error.fatalWechatBinding = true
      await pool.query(
        `UPDATE messages SET wechat_delivery_error=$1 WHERE id=$2`,
        [deliveryError, assistant.id]
      )
      throw error
    }
    if (!fallbackDelivery &&
        (error.retryable === false || assistant.wechat_delivery_attempts >= MAX_PRIMARY_DELIVERY_ATTEMPTS)) {
      const { rows } = await pool.query(
        `UPDATE messages
         SET wechat_delivery_status='fallback_pending',wechat_delivery_error=$1
         WHERE id=$2 RETURNING *`,
        [deliveryError, assistant.id]
      )
      return deliverWechatAssistant(binding, message, rows[0] || assistant, signal)
    }
    if (fallbackDelivery &&
        (error.retryable === false || assistant.wechat_delivery_attempts >= MAX_TOTAL_DELIVERY_ATTEMPTS)) {
      const { rows } = await pool.query(
        `UPDATE messages
         SET wechat_delivery_status='failed',wechat_delivery_error=$1
         WHERE id=$2 RETURNING *`,
        [deliveryError, assistant.id]
      )
      await pool.query(
        `UPDATE wechat_bindings
         SET last_error='有微信回复投递失败，请在 App 查看',last_error_at=now(),updated_at=now()
         WHERE id=$1`,
        [binding.id]
      )
      return rows[0] || assistant
    }
    await pool.query(
      `UPDATE messages SET wechat_delivery_status=$1,wechat_delivery_error=$2 WHERE id=$3`,
      [fallbackDelivery ? 'fallback_pending' : 'pending', deliveryError, assistant.id]
    )
    throw error
  }
  const successStatus = fallbackDelivery ? 'fallback_sent' : 'sent'
  const { rows } = await pool.query(
    `UPDATE messages
     SET wechat_delivery_status=$1,wechat_delivered_at=now(),wechat_delivery_error=NULL
     WHERE id=$2 RETURNING *`,
    [successStatus, assistant.id]
  )
  await pool.query(
    `UPDATE wechat_bindings SET last_delivery_at=now(),updated_at=now() WHERE id=$1`,
    [binding.id]
  )
  return rows[0]
}

async function respondToWechatMessage(binding, message, signal) {
  if (message.message_type != null && Number(message.message_type) !== 1) return
  if (message.message_state != null && Number(message.message_state) !== 2) return
  const textContent = messageText(message)
  if (!message.from_user_id || !message.context_token) return
  const content = textContent || '[暂不支持的非文字消息]'
  const conversationId = await ensureWechatConversation(binding)

  const clientId = messageClientId(binding.id, message)
  const duplicate = await pool.query(
    `SELECT id FROM messages WHERE conversation_id=$1 AND source='wechat' AND client_id=$2`,
    [conversationId, clientId]
  )
  if (duplicate.rows[0]) {
    // Replayed batches skip replies that WeChat already accepted and retry
    // only the exact pending reply for this inbound message.
    const assistant = await findWechatAssistant(conversationId, clientId)
    if (assistant) {
      await deliverWechatAssistant(binding, message, assistant, signal)
      return
    }
  }

  if (!duplicate.rows[0]) {
    await pool.query(
      `INSERT INTO messages(conversation_id,role,content,source,client_id,created_at)
       VALUES($1,'user',$2,'wechat',$3,to_timestamp($4/1000.0))`,
      [conversationId, content, clientId, message.create_time_ms || Date.now()]
    )
    await pool.query(
      `UPDATE conversations SET unread_count=unread_count+1,last_message_preview=$1,last_message_at=now(),updated_at=now()
       WHERE id=$2`,
      [content.slice(0, 256), conversationId]
    )
    await pool.query(
      `UPDATE wechat_bindings SET last_message_at=now(),updated_at=now() WHERE id=$1`,
      [binding.id]
    )
  }

  if (!textContent) {
    const replyText = '我目前只能处理微信文字消息，请把内容改成文字再发一次喵～'
    const assistant = await saveWechatAssistant(conversationId, replyText, {
      emotion: 'gentle',
      replyToClientId: clientId,
    })
    await deliverWechatAssistant(binding, message, assistant, signal)
    return
  }

  const quota = duplicate.rows[0]
    ? { retry: true }
    : await consumeQuota(binding.user_id)
  if (!quota) {
    const text = '今日消息已用完喵，明天再来吧～'
    const assistant = await saveWechatAssistant(conversationId, text, {
      emotion: 'sad',
      isError: true,
      replyToClientId: clientId,
    })
    await deliverWechatAssistant(binding, message, assistant, signal)
    return
  }

  const { rows: convRows } = await pool.query(`SELECT * FROM conversations WHERE id=$1`, [conversationId])
  const conv = convRows[0]
  const { rows: personaRows } = conv.persona_id
    ? await pool.query(`SELECT system_prompt FROM personas WHERE id=$1`, [conv.persona_id])
    : { rows: [] }
  const memories = await getRelevantMemories(binding.user_id, content)
  // Queue only after retrieval so the current message cannot be echoed back as
  // a "past" memory in the same WeChat response.
  if (!duplicate.rows[0]) queueAutoMemory({ userId: binding.user_id, content: textContent })
  const memoryBlock = memories.length
    ? `\n\n[相关记忆]\n${memories.map(memory => `- ${memory.content}`).join('\n')}`
    : ''
  const { rows: history } = await pool.query(
    `SELECT role,content FROM messages WHERE conversation_id=$1 ORDER BY created_at DESC LIMIT 40`,
    [conversationId]
  )
  const messages = [
    {
      role: 'system',
      content: ensurePersonaEmotionPrompt(
        personaRows[0]?.system_prompt || '你是一只名叫“喵喵”的猫娘，温柔、活泼地陪伴用户。'
      ) + memoryBlock,
    },
    ...history.reverse().map(item => ({ role: item.role, content: item.content })),
  ]

  let responseText = ''
  await streamAI({
    provider: conv.model_provider,
    modelId: conv.model_id,
    messages,
    temperature: conv.temperature ?? 0.8,
    maxTokens: conv.max_tokens || 4096,
    onDelta: delta => { responseText += delta },
    onDone: async text => { responseText = text },
    signal,
  })
  const { emotion, action, cleanText } = extractMeta(responseText)
  const assistant = await saveWechatAssistant(conversationId, cleanText, {
    emotion,
    action,
    replyToClientId: clientId,
  })
  await deliverWechatAssistant(binding, message, assistant, signal)
}

async function persistAndSendWechatFailure(binding, message, error, signal) {
  const conversationId = await ensureWechatConversation(binding)
  const clientId = messageClientId(binding.id, message)
  const assistant = await findWechatAssistant(conversationId, clientId) || await saveWechatAssistant(
    conversationId,
    '微信回复失败了，请稍后再试，或在 App 中切换模型后重试。',
    { emotion: 'sad', isError: true, replyToClientId: clientId }
  )
  await deliverWechatAssistant(binding, message, assistant, signal)
}

function waitBeforeRetry(state, delayMs = 5_000) {
  return new Promise(resolve => {
    if (state.stopped) return resolve()
    const finish = () => {
      clearTimeout(timer)
      state.abortController.signal.removeEventListener('abort', finish)
      resolve()
    }
    const timer = setTimeout(finish, delayMs)
    state.abortController.signal.addEventListener('abort', finish, { once: true })
  })
}

function retryDelayMs(failureCount) {
  return Math.min(30_000, 5_000 * (2 ** Math.min(failureCount - 1, 3)))
    + Math.floor(Math.random() * 500)
}

async function recordWorkerFailure(binding, state, error) {
  state.failureCount = (state.failureCount || 0) + 1
  const delayMs = retryDelayMs(state.failureCount)
  try {
    await pool.query(
      `UPDATE wechat_bindings
       SET worker_status='reconnecting',last_error=$1,last_error_at=now(),
           consecutive_failures=$2,next_retry_at=now()+($3::int*interval '1 millisecond'),
           updated_at=now()
       WHERE id=$4`,
      [String(error.message || error).slice(0, 1000), state.failureCount, delayMs, binding.id]
    )
  } finally {
    await waitBeforeRetry(state, delayMs)
  }
}

async function workerLoop(binding, state) {
  await notifyClawbotStart({
    baseUrl: binding.base_url,
    botToken: decryptSecret(binding.bot_token),
  })
  while (!state.stopped) {
    try {
      await pool.query(
        `UPDATE wechat_bindings SET last_poll_started_at=now(),updated_at=now() WHERE id=$1`,
        [binding.id]
      )
      const response = await getClawbotUpdates({
        baseUrl: binding.base_url,
        botToken: decryptSecret(binding.bot_token),
        getUpdatesBuf: binding.get_updates_buf || '',
        signal: state.abortController.signal,
      })
      if (response.ret === -14 || response.errcode === -14) {
        state.exitStatus = 'expired'
        await pool.query(
          `UPDATE wechat_bindings
           SET worker_status='expired',last_error='微信登录已过期，请重新扫码绑定',
               last_error_at=now(),next_retry_at=NULL,updated_at=now()
           WHERE id=$1`,
          [binding.id]
        )
        return
      }
      await pool.query(
        `UPDATE wechat_bindings SET last_heartbeat_at=now(),updated_at=now() WHERE id=$1`,
        [binding.id]
      )
      const delivered = await deliverWechatBatch(response.msgs || [], {
        isStopped: () => state.stopped,
        respond: message => respondToWechatMessage(binding, message, state.abortController.signal),
        persistFailure: (message, error) => persistAndSendWechatFailure(
          binding,
          message,
          error,
          state.abortController.signal
        ),
      })
      if (!delivered) return

      // Persist the cursor first. If this write fails, the batch is replayed;
      // replies already accepted by WeChat are skipped by delivery status.
      if (Object.prototype.hasOwnProperty.call(response, 'get_updates_buf')) {
        await pool.query(
          `UPDATE wechat_bindings SET get_updates_buf=$1,updated_at=now() WHERE id=$2`,
          [response.get_updates_buf || '', binding.id]
        )
        binding.get_updates_buf = response.get_updates_buf || ''
      }
      state.failureCount = 0
      await pool.query(
        `UPDATE wechat_bindings
         SET worker_status='running',last_error=NULL,consecutive_failures=0,
             next_retry_at=NULL,last_heartbeat_at=now(),updated_at=now()
         WHERE id=$1`,
        [binding.id]
      )
    } catch (error) {
      if (state.stopped) return
      if (isExpiredBindingError(error) || error?.fatalWechatBinding) {
        state.exitStatus = 'expired'
        await pool.query(
          `UPDATE wechat_bindings
           SET worker_status='expired',last_error='微信登录已过期，请重新扫码绑定',
               last_error_at=now(),next_retry_at=NULL,updated_at=now()
           WHERE id=$1`,
          [binding.id]
        )
        return
      }
      await recordWorkerFailure(binding, state, error)
    }
  }
}

async function superviseWechatWorker(binding, state) {
  const heartbeat = setInterval(() => {
    pool.query(
      `UPDATE wechat_bindings SET last_heartbeat_at=now(),updated_at=now() WHERE id=$1`,
      [binding.id]
    ).catch(() => {})
  }, 15_000)
  heartbeat.unref?.()
  try {
    while (!state.stopped && state.exitStatus !== 'expired') {
      try {
        await workerLoop(binding, state)
        break
      } catch (error) {
        if (state.stopped) break
        await recordWorkerFailure(binding, state, error).catch(() => {})
      }
    }
  } finally {
    clearInterval(heartbeat)
    const finalStatus = state.exitStatus === 'expired' ? 'expired' : 'stopped'
    await pool.query(
      `UPDATE wechat_bindings SET worker_status=$1,next_retry_at=NULL,updated_at=now() WHERE id=$2`,
      [finalStatus, binding.id]
    ).catch(() => {})
  }
}

async function startWechatWorkerUnlocked(bindingId) {
  // Restarting a local worker must not reset the remote channel; channel reset
  // is reserved for explicit user/admin unbind operations.
  await stopWechatWorkerUnlocked(bindingId, false)
  const { rows } = await pool.query(`SELECT * FROM wechat_bindings WHERE id=$1 AND is_active=true`, [bindingId])
  if (!rows[0]) throw new Error('Active WeChat binding not found')
  const state = {
    stopped: false,
    abortController: new AbortController(),
    binding: rows[0],
    promise: null,
    failureCount: 0,
    exitStatus: null,
  }
  await pool.query(
    `UPDATE wechat_bindings
     SET worker_status='starting',next_retry_at=NULL,last_heartbeat_at=now(),updated_at=now()
     WHERE id=$1`,
    [bindingId]
  )
  workers.set(bindingId, state)
  state.promise = superviseWechatWorker(rows[0], state).finally(() => {
    if (workers.get(bindingId) === state) workers.delete(bindingId)
  })
}

async function stopWechatWorkerUnlocked(bindingId, notifyRemote = true) {
  const state = workers.get(bindingId)
  if (state) {
    state.stopped = true
    state.abortController.abort()
    await state.promise?.catch(() => {})
    if (notifyRemote) {
      await resetClawbotChannel({
        baseUrl: state.binding.base_url,
        botToken: decryptSecret(state.binding.bot_token),
      }).catch(error => pool.query(
        `UPDATE wechat_bindings SET last_error=$1,updated_at=now() WHERE id=$2`,
        [`channel reset failed: ${String(error.message || error)}`.slice(0, 1000), bindingId]
      ))
    }
    if (workers.get(bindingId) === state) workers.delete(bindingId)
  }
}

export function startWechatWorker(bindingId) {
  return serializeWorkerTransition(bindingId, () => startWechatWorkerUnlocked(bindingId))
}

export function stopWechatWorker(bindingId, notifyRemote = true) {
  return serializeWorkerTransition(
    bindingId,
    () => stopWechatWorkerUnlocked(bindingId, notifyRemote)
  )
}

export async function startAllWechatWorkers() {
  const { rows } = await pool.query(`SELECT id FROM wechat_bindings WHERE is_active=true`)
  for (const row of rows) await startWechatWorker(row.id)
}

export async function stopAllWechatWorkers() {
  const ids = [...workers.keys()]
  await Promise.all(ids.map(id => stopWechatWorker(id, false)))
}

export function activeWechatWorkerIds() {
  return [...workers.keys()]
}
