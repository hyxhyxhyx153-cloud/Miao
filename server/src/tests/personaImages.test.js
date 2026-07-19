import test from 'node:test'
import assert from 'node:assert/strict'
import { pool } from '../db/client.js'

const BASE = 'http://localhost:3000/api/v1'
const runId = Date.now()
const account = {
  username: `persona_${runId}`,
  email: `persona_${runId}@example.com`,
  password: 'testpassword123',
}
const uploadedUrls = [1, 2, 3, 4].map(
  number => `http://10.0.2.2:3000/api/v1/uploads/persona-test-${runId}-${number}.png`,
)

let token
let userId
let personaId

async function request(path, { method = 'GET', body } = {}) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  return { status: response.status, body: await response.json().catch(() => ({})) }
}

test('registers a persona reference test account', async () => {
  const result = await request('/auth/register', { method: 'POST', body: account })
  assert.equal(result.status, 200)
  token = result.body.accessToken
  userId = result.body.user.id
})

test('creates and returns a persona with three ordered references', async () => {
  const result = await request('/personas', {
    method: 'POST',
    body: {
      name: '视觉人格',
      description: '测试人格参考图',
      systemPrompt: '保持角色形象一致。',
      referenceImageUrls: uploadedUrls.slice(0, 3),
    },
  })
  assert.equal(result.status, 200, JSON.stringify(result.body))
  assert.match(result.body.systemPrompt, /^保持角色形象一致。/)
  assert.match(result.body.systemPrompt, /<!--\{"emotion":"gentle","action":null,"generateImage":false,"imagePrompt":null\}-->/)
  assert.deepEqual(result.body.referenceImageUrls, uploadedUrls.slice(0, 3))
  personaId = result.body.id

  const list = await request('/personas')
  const persona = list.body.find(item => item.id === personaId)
  assert.equal(persona.systemPrompt, result.body.systemPrompt)
  assert.deepEqual(persona.referenceImageUrls, uploadedUrls.slice(0, 3))
})

test('rejects four references and non-upload URLs', async () => {
  const tooMany = await request(`/personas/${personaId}`, {
    method: 'PATCH',
    body: { referenceImageUrls: uploadedUrls },
  })
  assert.equal(tooMany.status, 400)

  const external = await request(`/personas/${personaId}`, {
    method: 'PATCH',
    body: { referenceImageUrls: ['https://example.com/reference.png'] },
  })
  assert.equal(external.status, 400)
})

test('keeps emotion metadata when a custom persona prompt is replaced', async () => {
  const result = await request(`/personas/${personaId}`, {
    method: 'PATCH',
    body: { systemPrompt: '现在是一位沉稳的聊天伙伴。' },
  })
  assert.equal(result.status, 200, JSON.stringify(result.body))
  assert.match(result.body.systemPrompt, /^现在是一位沉稳的聊天伙伴。/)
  assert.match(result.body.systemPrompt, /<!--\{"emotion":"gentle","action":null,"generateImage":false,"imagePrompt":null\}-->/)
})

test('supports explicitly clearing persona references', async () => {
  const result = await request(`/personas/${personaId}`, {
    method: 'PATCH',
    body: { referenceImageUrls: [] },
  })
  assert.equal(result.status, 200)
  assert.deepEqual(result.body.referenceImageUrls, [])
})

test('cleans up persona reference test data', async () => {
  if (userId) await pool.query('DELETE FROM users WHERE id=$1', [userId])
  await pool.end()
})
