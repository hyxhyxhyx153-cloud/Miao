import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { cosineSimilarity } from '../services/embeddings.js'
import { ipMatchesRule, isIpAllowed } from '../services/ipAccess.js'
import { decryptSecret, encryptSecret, isEncryptedSecret } from '../services/secretVault.js'
import { notifyClawbotStart, resetClawbotChannel, sendClawbotMessage } from '../services/clawbot.js'
import { buildEmojiPreview, buildEmojiUserContent, toModelHistoryMessage } from '../services/emojiContext.js'
import { deliverWechatBatch } from '../services/wechatDelivery.js'
import { buildWechatBindingStatus } from '../services/wechatStatus.js'
import {
  buildEmojiCandidateDecisionMessages,
  parseEmojiNumberDecision,
  parseEmojiSendDecision,
  parseEmojiTagDecision,
} from '../services/emojiReplyDecision.js'

test('secret vault encrypts and decrypts values without deterministic ciphertext', () => {
  const first = encryptSecret('sk-test-secret')
  const second = encryptSecret('sk-test-secret')
  assert.equal(isEncryptedSecret(first), true)
  assert.notEqual(first, second)
  assert.equal(decryptSecret(first), 'sk-test-secret')
  assert.equal(decryptSecret(second), 'sk-test-secret')
})

test('secret vault keeps legacy plaintext readable for migration', () => {
  assert.equal(decryptSecret('legacy-secret'), 'legacy-secret')
})

test('cosine similarity ranks matching vectors above unrelated vectors', () => {
  assert.equal(cosineSimilarity([1, 0], [1, 0]), 1)
  assert.equal(cosineSimilarity([1, 0], [0, 1]), 0)
  assert.equal(cosineSimilarity([1], [1, 0]), -1)
})

test('admin IP rules support exact IPv4, mapped IPv4 and CIDR ranges', () => {
  assert.equal(ipMatchesRule('127.0.0.1', '127.0.0.1'), true)
  assert.equal(ipMatchesRule('::ffff:127.0.0.1', '127.0.0.1'), true)
  assert.equal(ipMatchesRule('10.23.4.5', '10.0.0.0/8'), true)
  assert.equal(ipMatchesRule('192.168.1.2', '10.0.0.0/8'), false)
  assert.equal(ipMatchesRule('2001:db8::12', '2001:db8::/32'), true)
  assert.equal(isIpAllowed('203.0.113.8', []), true)
})

test('emoji context tells the model the trusted label and description', () => {
  const emoji = {
    emotion_tag: '开心',
    description: '  小猫   开心地挥手  ',
  }
  assert.equal(
    buildEmojiUserContent(emoji),
    '用户发送了一个表情包。\n表情标签：开心\n表情描述：小猫 开心地挥手'
  )
  assert.equal(buildEmojiPreview(emoji), '[开心表情] 小猫 开心地挥手')
})

test('model history keeps real images but never sends emoji display URLs as vision input', () => {
  assert.deepEqual(
    toModelHistoryMessage({
      role: 'user',
      content: '用户发送了一个表情包。',
      content_type: 'emoji',
      media_urls: ['https://example.com/emoji.gif'],
    }),
    { role: 'user', content: '用户发送了一个表情包。', mediaUrls: [] }
  )
  assert.deepEqual(
    toModelHistoryMessage({
      role: 'user',
      content: '看图',
      content_type: 'image',
      media_urls: ['https://example.com/image.png'],
    }),
    { role: 'user', content: '看图', mediaUrls: ['https://example.com/image.png'] }
  )
})

test('WeChat connection status uses live worker state and a dedicated heartbeat', () => {
  const now = Date.parse('2026-07-17T14:00:00.000Z')
  const row = {
    worker_status: 'running',
    last_heartbeat_at: new Date(now - 12_000),
    last_message_at: new Date(now - 60_000),
    last_delivery_at: new Date(now - 5_000),
  }
  const online = buildWechatBindingStatus(row, { workerActive: true, now })
  assert.equal(online.connection_status, 'running')
  assert.equal(online.heartbeat_age_seconds, 12)
  assert.equal(online.last_message_age_seconds, 60)
  assert.equal(online.last_delivery_age_seconds, 5)
  assert.equal(
    buildWechatBindingStatus(row, { workerActive: false, now }).connection_status,
    'offline'
  )
  assert.equal(
    buildWechatBindingStatus({ ...row, worker_status: 'reconnecting' }, { workerActive: true, now }).connection_status,
    'reconnecting'
  )
  assert.equal(
    buildWechatBindingStatus({ ...row, worker_status: 'expired' }, { workerActive: false, now }).connection_status,
    'expired'
  )
})

test('WeChat batch delivery rejects when the fallback reply is not delivered', async () => {
  const persisted = []
  const recovered = await deliverWechatBatch([{ id: 1 }, { id: 2 }], {
    isStopped: () => false,
    respond: async message => {
      if (message.id === 1) throw new Error('generation failed')
    },
    persistFailure: async message => { persisted.push(message.id) },
  })
  assert.equal(recovered, true)
  assert.deepEqual(persisted, [1])

  await assert.rejects(
    deliverWechatBatch([{ id: 3 }], {
      isStopped: () => false,
      respond: async () => { throw new Error('send failed') },
      persistFailure: async () => { throw new Error('retry send failed') },
    }),
    /retry send failed/
  )

  let stopped = false
  const stoppedResult = await deliverWechatBatch([{ id: 4 }, { id: 5 }], {
    isStopped: () => stopped,
    respond: async () => { stopped = true },
    persistFailure: async () => {},
  })
  assert.equal(stoppedResult, false)
})

test('ClawBot send uses a stable completed-bot message envelope and rejects errcode failures', async () => {
  const received = []
  const replies = [{ ret: 0 }, { ret: 0, errcode: -14, errmsg: 'expired' }]
  const server = http.createServer((request, response) => {
    let body = ''
    request.setEncoding('utf8')
    request.on('data', chunk => { body += chunk })
    request.on('end', () => {
      received.push(JSON.parse(body))
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify(replies.shift()))
    })
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const baseUrl = `http://127.0.0.1:${server.address().port}`
    const request = {
      baseUrl,
      botToken: 'test-token',
      toUserId: 'wechat-user',
      contextToken: 'context-token',
      clientId: 'stable-client-id',
      text: '回复内容',
    }
    await sendClawbotMessage(request)
    assert.deepEqual(received[0].msg, {
      from_user_id: '',
      to_user_id: 'wechat-user',
      client_id: 'stable-client-id',
      context_token: 'context-token',
      message_type: 2,
      message_state: 2,
      item_list: [{ type: 1, text_item: { text: '回复内容' } }],
    })
    await assert.rejects(sendClawbotMessage(request), /expired/)
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
})

test('ClawBot send rejects a response stream that closes before valid JSON completes', async () => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.write('{"ret":')
    response.socket.destroy()
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const sending = sendClawbotMessage({
      baseUrl: `http://127.0.0.1:${server.address().port}`,
      botToken: 'test-token',
      toUserId: 'wechat-user',
      contextToken: 'context-token',
      clientId: 'stable-client-id',
      text: '回复内容',
    })
    await assert.rejects(
      Promise.race([
        sending,
        new Promise((_, reject) => setTimeout(() => reject(new Error('request remained pending')), 2_000)),
      ]),
      /interrupted|closed before completion|socket hang up/
    )
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
})

test('ClawBot lifecycle uses authenticated start and channel reset endpoints', async () => {
  const received = []
  const server = http.createServer((request, response) => {
    let body = ''
    request.setEncoding('utf8')
    request.on('data', chunk => { body += chunk })
    request.on('end', () => {
      received.push({ url: request.url, headers: request.headers, body: JSON.parse(body) })
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end('{"ret":0}')
    })
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const baseUrl = `http://127.0.0.1:${server.address().port}`
    await notifyClawbotStart({ baseUrl, botToken: 'test-token' })
    await resetClawbotChannel({ baseUrl, botToken: 'test-token' })
  } finally {
    await new Promise(resolve => server.close(resolve))
  }

  assert.deepEqual(received.map(item => item.url), [
    '/ilink/bot/msg/notifystart',
    '/ilink/bot/msg/notifystop',
  ])
  assert.equal(received[0].headers.authorization, 'Bearer test-token')
  assert.equal(received[1].headers.authorizationtype, 'ilink_bot_token')
  assert.equal(received[1].body.base_info.bot_agent, 'Miao/1.0.0')
})

test('emoji reply decisions accept only valid JSON, allowed tags and candidate numbers', () => {
  assert.equal(parseEmojiSendDecision('{"sendEmoji":true}'), true)
  assert.equal(parseEmojiSendDecision('```json\n{"sendEmoji":false}\n```'), false)
  assert.equal(parseEmojiSendDecision('{"sendEmoji":"yes"}'), null)

  assert.equal(parseEmojiTagDecision('{"emotionTag":"happy"}', ['happy', 'sad']), 'happy')
  assert.equal(parseEmojiTagDecision('{"emotionTag":"invented"}', ['happy', 'sad']), null)
  assert.equal(parseEmojiNumberDecision('{"number":2}', 3), 2)
  assert.equal(parseEmojiNumberDecision('{"number":4}', 3), null)

  const messages = buildEmojiCandidateDecisionMessages({
    userContent: '谢谢你',
    assistantText: '不客气喵～',
    tag: 'happy',
    emojis: [
      {
        filename: 'thanks.png',
        emotion_tag: 'happy',
        description: '闭眼灿笑并写着谢谢',
        scene_keywords: ['感谢'],
      },
      {
        filename: 'smile.png',
        emotion_tag: 'happy',
        description: '开心微笑',
        scene_keywords: [],
      },
    ],
  })
  const payload = JSON.parse(messages[1].content)
  assert.deepEqual(payload.allEmojiCandidates.map(item => item.number), [1, 2])
  assert.equal(payload.allEmojiCandidates[0].description, '闭眼灿笑并写着谢谢')
})
