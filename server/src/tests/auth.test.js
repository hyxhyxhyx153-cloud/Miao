/**
 * Auth flow integration tests
 * Run with: node src/tests/auth.test.js
 * Requires the server to be running on http://localhost:3000
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pool } from '../db/client.js'

const BASE = 'http://localhost:3000/api/v1'

// Unique test user per run to avoid conflicts
const RUN_ID = Date.now()
const TEST_USER = {
  username: `testuser_${RUN_ID}`,
  email: `testuser_${RUN_ID}@example.com`,
  password: 'testpassword123',
}

let accessToken = null
let refreshToken = null
let userId = null

// ── Helper ────────────────────────────────────────────────────────────────────

async function post(path, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  })
  const json = await res.json().catch(() => ({}))
  return { status: res.status, body: json }
}

async function get(path, token) {
  const headers = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}${path}`, { headers })
  const json = await res.json().catch(() => ({}))
  return { status: res.status, body: json }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

test('1. Register a new user', async () => {
  const { status, body } = await post('/auth/register', TEST_USER)
  assert.equal(status, 200, `Expected 200, got ${status}: ${JSON.stringify(body)}`)
  assert.ok(body.accessToken, 'Response should contain accessToken')
  assert.ok(body.refreshToken, 'Response should contain refreshToken')
  assert.ok(body.user, 'Response should contain user object')
  assert.equal(body.user.username, TEST_USER.username)
  assert.equal(body.user.email, TEST_USER.email)

  // Store for subsequent tests
  accessToken = body.accessToken
  refreshToken = body.refreshToken
  userId = body.user.id
})

test('2. Login with email', async () => {
  const { status, body } = await post('/auth/login', {
    email: TEST_USER.email,
    password: TEST_USER.password,
  })
  assert.equal(status, 200, `Expected 200, got ${status}: ${JSON.stringify(body)}`)
  assert.ok(body.accessToken, 'Should return accessToken on email login')
  assert.ok(body.refreshToken, 'Should return refreshToken on email login')
  assert.equal(body.user.email, TEST_USER.email)

  accessToken = body.accessToken
  refreshToken = body.refreshToken
})

test('3. Login with username', async () => {
  const { status, body } = await post('/auth/login', {
    email: TEST_USER.username,   // The login endpoint accepts username in the email field
    password: TEST_USER.password,
  })
  assert.equal(status, 200, `Expected 200, got ${status}: ${JSON.stringify(body)}`)
  assert.ok(body.accessToken, 'Should return accessToken on username login')
  assert.equal(body.user.username, TEST_USER.username)
})

test('4. Refresh access token', async () => {
  const { status, body } = await post('/auth/refresh', { refreshToken })
  assert.equal(status, 200, `Expected 200, got ${status}: ${JSON.stringify(body)}`)
  assert.ok(body.accessToken, 'Should return new accessToken')
  assert.ok(body.refreshToken, 'Should return new refreshToken')
  assert.notEqual(body.accessToken, accessToken, 'New access token should differ from old one')

  accessToken = body.accessToken
  refreshToken = body.refreshToken
})

test('5. Get user profile (authenticated)', async () => {
  const { status, body } = await get('/user/profile', accessToken)
  assert.equal(status, 200, `Expected 200, got ${status}: ${JSON.stringify(body)}`)
  assert.ok(body.id || body.username, 'Profile response should contain user data')
})

test('6. Login with wrong password returns 401', async () => {
  const { status, body } = await post('/auth/login', {
    email: TEST_USER.email,
    password: 'wrongpassword!',
  })
  assert.equal(status, 401, `Expected 401, got ${status}: ${JSON.stringify(body)}`)
  assert.ok(body.error, 'Should return error message')
})

test('7. Duplicate registration returns 409', async () => {
  const { status, body } = await post('/auth/register', TEST_USER)
  assert.equal(status, 409, `Expected 409 for duplicate user, got ${status}: ${JSON.stringify(body)}`)
})

test('8. Accessing protected route without token returns 401', async () => {
  const { status } = await get('/user/profile', null)
  assert.equal(status, 401, `Expected 401 without token, got ${status}`)
})

test('9. Logout', async () => {
  const { status, body } = await post('/auth/logout', {}, accessToken)
  assert.equal(status, 200, `Expected 200, got ${status}: ${JSON.stringify(body)}`)
  assert.equal(body.success, true)
})

// ── Cleanup: delete test user ─────────────────────────────────────────────────

test('cleanup: delete test user', async () => {
  // Re-login to get a fresh token (logout is stateless, token still valid)
  const { status: loginStatus, body: loginBody } = await post('/auth/login', {
    email: TEST_USER.email,
    password: TEST_USER.password,
  })
  assert.equal(loginStatus, 200, 'Should be able to re-login for cleanup')

  const freshToken = loginBody.accessToken

  const { status } = await get('/user/profile', freshToken)
  assert.equal(status, 200, 'User should still be accessible before cleanup')
  await pool.query(`DELETE FROM users WHERE id=$1`, [userId])
  await pool.end()
})
