import test from 'node:test'
import assert from 'node:assert/strict'
import { pool } from '../db/client.js'
import { hasPersonaImageMeta } from '../services/personaPrompt.js'

const BASE = 'http://localhost:3000/api/v1'
const runId = Date.now()
const builtinId = '00000000-0000-0000-0000-000000000001'
const referenceUrl = `http://10.0.2.2:3000/api/v1/uploads/persona-override-${runId}.png`

const accounts = ['owner', 'other', 'admin'].map(kind => ({
  kind,
  username: `persona_${kind}_${runId}`,
  email: `persona_${kind}_${runId}@example.com`,
  password: 'testpassword123',
}))

const users = new Map()
let sourceBefore
let overrideId
let conversationId

async function request(path, { method = 'GET', body, token } = {}) {
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

test('registers isolated owner, observer and admin accounts', async () => {
  for (const account of accounts) {
    const result = await request('/auth/register', { method: 'POST', body: account })
    assert.equal(result.status, 200, JSON.stringify(result.body))
    users.set(account.kind, {
      id: result.body.user.id,
      token: result.body.accessToken,
    })
  }
  await pool.query(`UPDATE users SET role='admin' WHERE id=$1`, [users.get('admin').id])
  const adminAccount = accounts.find(account => account.kind === 'admin')
  const adminLogin = await request('/auth/login', {
    method: 'POST',
    body: { email: adminAccount.email, password: adminAccount.password },
  })
  assert.equal(adminLogin.status, 200, JSON.stringify(adminLogin.body))
  users.get('admin').token = adminLogin.body.accessToken
  const { rows } = await pool.query(`SELECT * FROM personas WHERE id=$1`, [builtinId])
  sourceBefore = rows[0]
  assert.ok(sourceBefore?.is_builtin)
})

test('user editing a builtin upserts a private override and migrates conversations', async () => {
  const owner = users.get('owner')
  const { rows } = await pool.query(
    `INSERT INTO conversations(user_id,title,model_provider,model_id,persona_id)
     VALUES($1,'persona override test','openai','gpt-4o',$2) RETURNING id`,
    [owner.id, builtinId]
  )
  conversationId = rows[0].id

  const first = await request(`/personas/${builtinId}`, {
    method: 'PATCH',
    token: owner.token,
    body: {
      name: `我的喵喵 ${runId}`,
      description: '只属于当前用户',
      systemPrompt: '这是当前用户的专属系统提示词。',
      referenceImageUrls: [referenceUrl],
    },
  })
  assert.equal(first.status, 200, JSON.stringify(first.body))
  assert.notEqual(first.body.id, builtinId)
  assert.equal(first.body.isBuiltin, false)
  assert.equal(first.body.sourcePersonaId, builtinId)
  assert.match(first.body.systemPrompt, /^这是当前用户的专属系统提示词。/)
  assert.match(first.body.systemPrompt, /<!--\{"emotion":"gentle","action":null,"generateImage":false,"imagePrompt":null\}-->/)
  assert.deepEqual(first.body.referenceImageUrls, [referenceUrl])
  overrideId = first.body.id

  const second = await request(`/personas/${builtinId}`, {
    method: 'PATCH',
    token: owner.token,
    body: { name: `再次修改 ${runId}` },
  })
  assert.equal(second.status, 200, JSON.stringify(second.body))
  assert.equal(second.body.id, overrideId, 'editing the source twice must reuse one override')
  assert.equal(second.body.description, '只属于当前用户')
  assert.equal(second.body.systemPrompt, first.body.systemPrompt)
  assert.deepEqual(second.body.referenceImageUrls, [referenceUrl])

  const clearedDescription = await request(`/personas/${builtinId}`, {
    method: 'PATCH',
    token: owner.token,
    body: { description: null },
  })
  assert.equal(clearedDescription.status, 200, JSON.stringify(clearedDescription.body))
  assert.equal(clearedDescription.body.id, overrideId)
  assert.equal(clearedDescription.body.description, '')
  assert.equal(clearedDescription.body.systemPrompt, first.body.systemPrompt)
  assert.deepEqual(clearedDescription.body.referenceImageUrls, [referenceUrl])

  const { rows: sourceRows } = await pool.query(`SELECT * FROM personas WHERE id=$1`, [builtinId])
  assert.equal(sourceRows[0].name, sourceBefore.name, 'global builtin must remain unchanged')
  assert.equal(sourceRows[0].system_prompt, sourceBefore.system_prompt)

  const { rows: conversationRows } = await pool.query(
    `SELECT persona_id FROM conversations WHERE id=$1`,
    [conversationId]
  )
  assert.equal(conversationRows[0].persona_id, overrideId)
})

test('only the owner sees the override in place of its source builtin', async () => {
  const ownerList = await request('/personas', { token: users.get('owner').token })
  assert.equal(ownerList.status, 200)
  assert.ok(ownerList.body.some(persona => persona.id === overrideId))
  assert.ok(!ownerList.body.some(persona => persona.id === builtinId))

  const otherList = await request('/personas', { token: users.get('other').token })
  assert.equal(otherList.status, 200)
  assert.ok(otherList.body.some(persona => persona.id === builtinId))
  assert.ok(!otherList.body.some(persona => persona.id === overrideId))
})

test('deleting the private override restores the builtin and conversation reference', async () => {
  const owner = users.get('owner')
  const protectedDelete = await request(`/personas/${builtinId}`, {
    method: 'DELETE',
    token: owner.token,
  })
  assert.equal(protectedDelete.status, 404)

  const deleted = await request(`/personas/${overrideId}`, {
    method: 'DELETE',
    token: owner.token,
  })
  assert.equal(deleted.status, 200, JSON.stringify(deleted.body))

  const list = await request('/personas', { token: owner.token })
  assert.ok(list.body.some(persona => persona.id === builtinId))
  assert.ok(!list.body.some(persona => persona.id === overrideId))

  const { rows } = await pool.query(`SELECT persona_id FROM conversations WHERE id=$1`, [conversationId])
  assert.equal(rows[0].persona_id, builtinId)
})

test('admin can edit the global builtin while it remains protected from deletion', async () => {
  const adminToken = users.get('admin').token
  try {
    const updated = await request(`/admin/personas/${builtinId}`, {
      method: 'PATCH',
      token: adminToken,
      body: {
        name: `后台可编辑 ${runId}`,
        description: '',
        systemPrompt: `后台系统提示词 ${runId}`,
        referenceImageUrls: [referenceUrl],
      },
    })
    assert.equal(updated.status, 200, JSON.stringify(updated.body))
    assert.equal(updated.body.name, `后台可编辑 ${runId}`)
    assert.equal(updated.body.description, '')
    assert.ok(updated.body.system_prompt.startsWith(`后台系统提示词 ${runId}\n\n`))
    assert.equal(hasPersonaImageMeta(updated.body.system_prompt), true)
    assert.deepEqual(updated.body.reference_image_urls, [referenceUrl])

    const deleted = await request(`/admin/personas/${builtinId}`, {
      method: 'DELETE',
      token: adminToken,
    })
    assert.equal(deleted.status, 404)
  } finally {
    await pool.query(
      `UPDATE personas SET
         name=$1,description=$2,system_prompt=$3,reference_image_urls=$4,updated_at=$5
       WHERE id=$6`,
      [
        sourceBefore.name,
        sourceBefore.description,
        sourceBefore.system_prompt,
        sourceBefore.reference_image_urls,
        sourceBefore.updated_at,
        builtinId,
      ]
    )
  }
})

test('cleans up persona override regression data', async () => {
  for (const user of users.values()) {
    await pool.query(`DELETE FROM users WHERE id=$1`, [user.id])
  }
  await pool.end()
})
