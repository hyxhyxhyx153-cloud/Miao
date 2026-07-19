import { test } from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import { createServer } from 'node:http'
import { pool } from '../db/client.js'
import { ensureWechatConversation } from '../services/wechatWorker.js'

const BASE = 'http://localhost:3000/api/v1'
const runId = Date.now()
const account = {
  username: `regression_${runId}`,
  email: `regression_${runId}@example.com`,
  password: 'regression-password-123',
}
const regressionModels = {
  primary: { modelId: `regression-deepseek-${runId}`, provider: 'deepseek' },
  secondary: { modelId: `regression-openai-${runId}`, provider: 'openai' },
  disabled: { modelId: `regression-disabled-${runId}`, provider: 'zhipu' },
}

let userId
let accessToken
let refreshToken
let oldLogId

async function request(path, { method = 'GET', body, token = accessToken } = {}) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const payload = await response.json().catch(() => ({}))
  return { status: response.status, body: payload }
}

test('registers a regression account', async () => {
  const result = await request('/auth/register', { method: 'POST', body: account, token: null })
  assert.equal(result.status, 200, JSON.stringify(result.body))
  userId = result.body.user.id
  accessToken = result.body.accessToken
  refreshToken = result.body.refreshToken
  await pool.query(
    `INSERT INTO model_configs(model_id,provider,display_name,supports_vision,description,is_enabled)
     VALUES($1,$2,'Regression primary',false,'integration test only',true),
           ($3,$4,'Regression secondary',true,'integration test only',true),
           ($5,$6,'Regression disabled',false,'integration test only',false)`,
    [
      regressionModels.primary.modelId,
      regressionModels.primary.provider,
      regressionModels.secondary.modelId,
      regressionModels.secondary.provider,
      regressionModels.disabled.modelId,
      regressionModels.disabled.provider,
    ]
  )
})

test('refresh tokens rotate and cannot be replayed', async () => {
  const original = refreshToken
  const first = await request('/auth/refresh', { method: 'POST', body: { refreshToken: original }, token: null })
  assert.equal(first.status, 200, JSON.stringify(first.body))
  const replay = await request('/auth/refresh', { method: 'POST', body: { refreshToken: original }, token: null })
  assert.equal(replay.status, 401)
  const second = await request('/auth/refresh', { method: 'POST', body: { refreshToken: first.body.refreshToken }, token: null })
  assert.equal(second.status, 200, JSON.stringify(second.body))
  accessToken = second.body.accessToken
  refreshToken = second.body.refreshToken
})

test('protected routes re-check banned and inactive account state', async () => {
  await pool.query(`UPDATE users SET is_banned=true WHERE id=$1`, [userId])
  assert.equal((await request('/user/profile')).status, 401)
  await pool.query(`UPDATE users SET is_banned=false,is_active=false WHERE id=$1`, [userId])
  assert.equal((await request('/user/profile')).status, 401)
  await pool.query(`UPDATE users SET is_active=true WHERE id=$1`, [userId])
  assert.equal((await request('/user/profile')).status, 200)
})

test('admin IP whitelist blocks and permits requests', async () => {
  await pool.query(`UPDATE users SET role='admin' WHERE id=$1`, [userId])
  await pool.query(
    `INSERT INTO system_settings(key,value) VALUES('ip_whitelist',$1)
     ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value`,
    [JSON.stringify(['192.0.2.10'])]
  )
  assert.equal((await request('/admin/stats')).status, 403)
  await pool.query(`UPDATE system_settings SET value=$1 WHERE key='ip_whitelist'`, [JSON.stringify(['127.0.0.1', '::1'])])
  assert.equal((await request('/admin/stats')).status, 200)
  await pool.query(`UPDATE system_settings SET value='[]'::jsonb WHERE key='ip_whitelist'`)
})

test('admin can save an API key offline and preserve or clear its Base URL', async () => {
  await pool.query(`UPDATE system_settings SET value=$1 WHERE key='ip_whitelist'`, [JSON.stringify(['127.0.0.1', '::1'])])
  let keyId
  try {
    const created = await request('/admin/api-keys', {
      method: 'POST',
      body: {
        provider: 'openai',
        apiKey: '  offline-regression-api-key  ',
        baseUrl: 'https://gateway.example.com/',
        note: 'offline save regression',
      },
    })
    assert.equal(created.status, 200, JSON.stringify(created.body))
    assert.equal(created.body.base_url, 'https://gateway.example.com/')
    keyId = created.body.id

    const saved = await pool.query(
      `SELECT base_url FROM api_keys WHERE id=$1`,
      [keyId]
    )
    assert.equal(saved.rows[0].base_url, 'https://gateway.example.com/')

    const cleared = await request(`/admin/api-keys/${keyId}`, {
      method: 'PATCH',
      body: { baseUrl: '' },
    })
    assert.equal(cleared.status, 200, JSON.stringify(cleared.body))
    assert.equal(cleared.body.base_url, null)

    const offlineValidation = await request('/admin/api-keys/validate', {
      method: 'POST',
      body: {
        provider: 'openai',
        apiKey: 'offline-regression-api-key',
        baseUrl: 'http://127.0.0.1:65534/v1',
      },
    })
    assert.equal(offlineValidation.status, 503, JSON.stringify(offlineValidation.body))
    assert.equal(offlineValidation.body.code, 'API_CONNECTION_REFUSED')
    assert.equal(offlineValidation.body.retryable, true)
    assert.equal(offlineValidation.body.canSave, true)
  } finally {
    if (keyId) await request(`/admin/api-keys/${keyId}`, { method: 'DELETE' })
    await pool.query(`UPDATE system_settings SET value='[]'::jsonb WHERE key='ip_whitelist'`)
  }
})

test('admin model validation falls back from root HTML to runtime /v1 and caches the result', async () => {
  await pool.query(`UPDATE system_settings SET value=$1 WHERE key='ip_whitelist'`, [JSON.stringify(['127.0.0.1', '::1'])])
  const modelIds = [
    `fallback-chat-a-${runId}`,
    `fallback-chat-b-${runId}`,
    `fallback-vision-c-${runId}`,
  ]
  const requestedPaths = []
  const upstream = createServer((req, res) => {
    requestedPaths.push(req.url)
    if (req.url === '/models') {
      res.writeHead(200, { 'content-type': 'text/html' })
      res.end('<!doctype html><html>gateway home</html>')
      return
    }
    if (req.url === '/v1/models') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({
        object: 'list',
        data: modelIds.map(id => ({ id, object: 'model' })),
      }))
      return
    }
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: { message: 'not found' } }))
  })
  await new Promise((resolve, reject) => {
    upstream.once('error', reject)
    upstream.listen(0, '127.0.0.1', resolve)
  })
  const address = upstream.address()
  const baseUrl = `http://127.0.0.1:${address.port}/`

  try {
    const first = await request('/admin/api-keys/validate', {
      method: 'POST',
      body: { provider: 'openai', apiKey: 'sk-fallback-test', baseUrl },
    })
    assert.equal(first.status, 200, JSON.stringify(first.body))
    assert.deepEqual(first.body.models.map(model => model.id), modelIds)
    assert.deepEqual(requestedPaths, ['/models', '/v1/models'])

    requestedPaths.length = 0
    const cached = await request('/admin/api-keys/validate', {
      method: 'POST',
      body: { provider: 'openai', apiKey: 'sk-fallback-test', baseUrl },
    })
    assert.equal(cached.status, 200, JSON.stringify(cached.body))
    assert.deepEqual(requestedPaths, ['/v1/models'])
  } finally {
    await new Promise(resolve => upstream.close(resolve))
    await pool.query(`DELETE FROM model_configs WHERE model_id = ANY($1::text[])`, [modelIds])
    await pool.query(`UPDATE system_settings SET value='[]'::jsonb WHERE key='ip_whitelist'`)
  }
})

test('offline sync is idempotent and preserves trusted emoji context', async () => {
  const createBody = {
    modelProvider: regressionModels.primary.provider,
    modelId: regressionModels.primary.modelId,
    title: '回归会话',
  }
  const firstConversation = await request('/conversations', { method: 'POST', body: createBody })
  const secondConversation = await request('/conversations', { method: 'POST', body: createBody })
  assert.equal(firstConversation.status, 200, JSON.stringify(firstConversation.body))
  assert.equal(secondConversation.status, 200, JSON.stringify(secondConversation.body))

  const clientId = crypto.randomUUID()
  const syncBody = {
    messages: [{ clientId, content: '', contentType: 'image', mediaUrls: ['https://example.com/image.png'] }],
  }
  const firstSync = await request(`/conversations/${firstConversation.body.id}/messages/sync`, { method: 'POST', body: syncBody })
  const retrySync = await request(`/conversations/${firstConversation.body.id}/messages/sync`, { method: 'POST', body: syncBody })
  const otherConversationSync = await request(`/conversations/${secondConversation.body.id}/messages/sync`, { method: 'POST', body: syncBody })
  assert.equal(firstSync.status, 200, JSON.stringify(firstSync.body))
  assert.equal(retrySync.status, 200, JSON.stringify(retrySync.body))
  assert.equal(otherConversationSync.status, 200, JSON.stringify(otherConversationSync.body))
  assert.equal(firstSync.body.synced[0].serverId, retrySync.body.synced[0].serverId)

  const messages = await request(`/conversations/${firstConversation.body.id}/messages`)
  assert.equal(messages.status, 200)
  assert.equal(messages.body.filter(message => message.clientId === clientId).length, 1)
  await pool.query(
    `INSERT INTO messages(conversation_id,role,content,reply_to_client_id)
     VALUES($1,'assistant','关联回复',$2)`,
    [firstConversation.body.id, clientId]
  )
  const messagesWithLinkedReply = await request(`/conversations/${firstConversation.body.id}/messages`)
  assert.equal(
    messagesWithLinkedReply.body.find(message => message.content === '关联回复')?.replyToClientId,
    clientId
  )

  const { rows: emojiRows } = await pool.query(
    `INSERT INTO emojis(filename,emotion_tag,description,url,is_active)
     VALUES($1,'开心','小猫开心地挥手','https://example.com/trusted-emoji.gif',true)
     RETURNING id`,
    [`regression-emoji-${runId}.gif`]
  )
  const emojiId = emojiRows[0].id
  const emojiClientId = crypto.randomUUID()
  const emojiSync = await request(`/conversations/${firstConversation.body.id}/messages/sync`, {
    method: 'POST',
    body: {
      messages: [{
        clientId: emojiClientId,
        content: '不可信客户端描述',
        contentType: 'emoji',
        mediaUrls: ['https://example.com/untrusted.gif'],
        emojiId,
      }],
    },
  })
  assert.equal(emojiSync.status, 200, JSON.stringify(emojiSync.body))
  const messagesWithEmoji = await request(`/conversations/${firstConversation.body.id}/messages`)
  const syncedEmoji = messagesWithEmoji.body.find(message => message.clientId === emojiClientId)
  assert.equal(syncedEmoji.contentType, 'emoji')
  assert.equal(syncedEmoji.emojiId, emojiId)
  assert.deepEqual(syncedEmoji.mediaUrls, ['https://example.com/trusted-emoji.gif'])
  assert.equal(
    syncedEmoji.content,
    '用户发送了一个表情包。\n表情标签：开心\n表情描述：小猫开心地挥手'
  )

  const invalidSyncClientId = crypto.randomUUID()
  const invalidEmojiSync = await request(`/conversations/${firstConversation.body.id}/messages/sync`, {
    method: 'POST',
    body: {
      messages: [{
        clientId: invalidSyncClientId,
        content: '伪造的表情描述',
        contentType: 'emoji',
        mediaUrls: ['https://example.com/untrusted.gif'],
        emojiId: crypto.randomUUID(),
      }],
    },
  })
  assert.equal(invalidEmojiSync.status, 400)
  const { rows: invalidSyncedRows } = await pool.query(
    `SELECT id FROM messages WHERE conversation_id=$1 AND client_id=$2`,
    [firstConversation.body.id, invalidSyncClientId]
  )
  assert.equal(invalidSyncedRows.length, 0)

  const forgedTypeClientId = crypto.randomUUID()
  const forgedTypeSync = await request(`/conversations/${firstConversation.body.id}/messages/sync`, {
    method: 'POST',
    body: {
      messages: [{
        clientId: forgedTypeClientId,
        content: '普通文本',
        contentType: 'emoji',
      }],
    },
  })
  assert.equal(forgedTypeSync.status, 200, JSON.stringify(forgedTypeSync.body))
  const forgedTypeMessages = await request(`/conversations/${firstConversation.body.id}/messages`)
  assert.equal(
    forgedTypeMessages.body.find(message => message.clientId === forgedTypeClientId)?.contentType,
    'text'
  )
  await pool.query(`DELETE FROM emojis WHERE id=$1`, [emojiId])

  const vision = await request(`/conversations/${firstConversation.body.id}/chat`, {
    method: 'POST',
    body: { content: '看图', mediaUrls: ['https://example.com/image.png'], clientId: crypto.randomUUID() },
  })
  assert.equal(vision.status, 400)

  const missingEmoji = await request(`/conversations/${firstConversation.body.id}/chat`, {
    method: 'POST',
    body: { content: '', emojiId: crypto.randomUUID(), clientId: crypto.randomUUID() },
  })
  assert.equal(missingEmoji.status, 400)
})

test('conversation settings validate and persist model and avatar changes', async () => {
  const created = await request('/conversations', {
    method: 'POST',
    body: {
      modelProvider: regressionModels.primary.provider,
      modelId: regressionModels.primary.modelId,
      title: '会话设置回归',
    },
  })
  assert.equal(created.status, 200, JSON.stringify(created.body))

  const switched = await request(`/conversations/${created.body.id}`, {
    method: 'PATCH',
    body: {
      modelId: regressionModels.secondary.modelId,
      userAvatarUrl: 'https://example.com/user.png',
      aiAvatarUrl: 'https://example.com/ai.png',
    },
  })
  assert.equal(switched.status, 200, JSON.stringify(switched.body))
  assert.equal(switched.body.modelId, regressionModels.secondary.modelId)
  assert.equal(switched.body.modelProvider, regressionModels.secondary.provider)
  assert.equal(switched.body.userAvatarUrl, 'https://example.com/user.png')
  assert.equal(switched.body.aiAvatarUrl, 'https://example.com/ai.png')

  const cleared = await request(`/conversations/${created.body.id}`, {
    method: 'PATCH',
    body: { userAvatarUrl: null },
  })
  assert.equal(cleared.status, 200, JSON.stringify(cleared.body))
  assert.equal(cleared.body.userAvatarUrl, null)
  assert.equal(cleared.body.aiAvatarUrl, 'https://example.com/ai.png')

  const invalidAvatar = await request(`/conversations/${created.body.id}`, {
    method: 'PATCH',
    body: { aiAvatarUrl: 'file:///private/avatar.png' },
  })
  assert.equal(invalidAvatar.status, 400)

  const disabledModel = await request(`/conversations/${created.body.id}`, {
    method: 'PATCH',
    body: { modelId: regressionModels.disabled.modelId },
  })
  assert.equal(disabledModel.status, 400)
  const modelList = await request('/models?includeDisabled=true')
  assert.equal(modelList.status, 200, JSON.stringify(modelList.body))
  assert.equal(modelList.body.find(model => model.modelId === regressionModels.disabled.modelId)?.isEnabled, false)
})

test('WeChat keeps its initial model and synchronizes the selected persona', async () => {
  await pool.query(
    `INSERT INTO user_settings(user_id,settings) VALUES($1,$2::jsonb)
     ON CONFLICT(user_id) DO UPDATE
     SET settings=user_settings.settings || EXCLUDED.settings,updated_at=now()`,
    [userId, JSON.stringify({
      modelProvider: regressionModels.primary.provider,
      modelId: regressionModels.primary.modelId,
    })]
  )
  const { rows: personaRows } = await pool.query(
    `INSERT INTO personas(user_id,name,description,system_prompt,is_builtin)
     VALUES($1,'微信回归人格','integration test only','你是微信回归人格。',false)
     RETURNING id`,
    [userId]
  )
  const personaId = personaRows[0].id
  const { rows } = await pool.query(
    `INSERT INTO wechat_bindings(user_id,bot_token,base_url,persona_id,is_active)
     VALUES($1,'integration-test-token','https://example.com',NULL,true)
     RETURNING *`,
    [userId]
  )
  const binding = rows[0]
  const personaBeforeFirstMessage = await request('/wechat/persona', {
    method: 'PATCH',
    body: { personaId },
  })
  assert.equal(personaBeforeFirstMessage.status, 200, JSON.stringify(personaBeforeFirstMessage.body))
  assert.equal(binding.persona_id, null)

  const firstId = await ensureWechatConversation(binding)
  const first = await pool.query(`SELECT model_provider,model_id,persona_id FROM conversations WHERE id=$1`, [firstId])
  assert.equal(first.rows[0].model_provider, regressionModels.primary.provider)
  assert.equal(first.rows[0].model_id, regressionModels.primary.modelId)
  assert.equal(first.rows[0].persona_id, personaId)

  await pool.query(
    `INSERT INTO user_settings(user_id,settings) VALUES($1,$2::jsonb)
     ON CONFLICT(user_id) DO UPDATE
     SET settings=user_settings.settings || EXCLUDED.settings,updated_at=now()`,
    [userId, JSON.stringify({
      modelProvider: regressionModels.secondary.provider,
      modelId: regressionModels.secondary.modelId,
    })]
  )
  await pool.query(`UPDATE wechat_bindings SET conversation_id=NULL WHERE id=$1`, [binding.id])
  binding.conversation_id = null
  const existingId = await ensureWechatConversation(binding)
  const existing = await pool.query(`SELECT model_provider,model_id FROM conversations WHERE id=$1`, [existingId])
  assert.equal(existingId, firstId)
  assert.equal(existing.rows[0].model_provider, regressionModels.primary.provider)
  assert.equal(existing.rows[0].model_id, regressionModels.primary.modelId)

  const resetPersona = await request('/wechat/persona', {
    method: 'PATCH',
    body: { personaId: '' },
  })
  assert.equal(resetPersona.status, 200, JSON.stringify(resetPersona.body))
  assert.equal(resetPersona.body.persona_id, null)

  const selectedPersona = await request('/wechat/persona', {
    method: 'PATCH',
    body: { personaId },
  })
  assert.equal(selectedPersona.status, 200, JSON.stringify(selectedPersona.body))
  assert.equal(selectedPersona.body.persona_id, personaId)
  assert.equal(selectedPersona.body.persona_name, '微信回归人格')

  const synced = await pool.query(
    `SELECT binding.persona_id AS binding_persona_id,conversation.persona_id AS conversation_persona_id
     FROM wechat_bindings binding
     JOIN conversations conversation ON conversation.id=binding.conversation_id
     WHERE binding.id=$1`,
    [binding.id]
  )
  assert.equal(synced.rows[0].binding_persona_id, personaId)
  assert.equal(synced.rows[0].conversation_persona_id, personaId)
})

test('admin log APIs expose at most the latest seven days', async () => {
  const marker = `retention_${runId}`
  const { rows } = await pool.query(
    `INSERT INTO request_logs(username,status,created_at) VALUES($1,'ok',now()-interval '8 days') RETURNING id`,
    [marker]
  )
  oldLogId = rows[0].id
  await pool.query(`INSERT INTO request_logs(username,status,created_at) VALUES($1,'ok',now())`, [marker])
  const list = await request(`/admin/logs?search=${encodeURIComponent(marker)}`)
  assert.equal(list.status, 200, JSON.stringify(list.body))
  assert.equal(list.body.total, 1)
  assert.equal((await request(`/admin/logs/${oldLogId}`)).status, 404)
})

test('cleanup regression data', async () => {
  await pool.query(`UPDATE system_settings SET value='[]'::jsonb WHERE key='ip_whitelist'`)
  await pool.query(`DELETE FROM request_logs WHERE username=$1`, [`retention_${runId}`])
  if (userId) await pool.query(`DELETE FROM users WHERE id=$1`, [userId])
  await pool.query(`DELETE FROM model_configs WHERE model_id = ANY($1::text[])`, [
    Object.values(regressionModels).map(model => model.modelId),
  ])
  await pool.end()
})
