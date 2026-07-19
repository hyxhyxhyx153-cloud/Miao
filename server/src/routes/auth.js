import { pool } from '../db/client.js'
import bcrypt from 'bcrypt'
import jwt from 'jsonwebtoken'
import crypto from 'node:crypto'
import { sendPasswordResetEmail } from '../services/mailer.js'

// ── Auth helpers ──────────────────────────────────────────────────────────────

export function makeTokens(fastify, userId, role) {
  // A unique jti prevents tokens issued within the same second from becoming
  // byte-for-byte identical, which would break refresh-token rotation.
  const accessToken = fastify.jwt.sign(
    { sub: userId, role, jti: crypto.randomUUID() },
    { expiresIn: '2h' }
  )
  const refreshToken = jwt.sign(
    { sub: userId, role, type: 'refresh', jti: crypto.randomUUID() },
    process.env.JWT_REFRESH_SECRET || 'refresh_secret',
    { expiresIn: '30d' }
  )
  return { accessToken, refreshToken }
}

function tokenHash(token) {
  return crypto.createHash('sha256').update(token).digest('hex')
}

async function storeRefreshToken(userId, refreshToken, client = pool) {
  await client.query(
    `INSERT INTO refresh_tokens(user_id,token_hash,expires_at)
     VALUES($1,$2,now()+interval '30 days')`,
    [userId, tokenHash(refreshToken)]
  )
}

export function userToDto(u) {
  return {
    id: u.id,
    username: u.username,
    email: u.email,
    nickname: u.nickname || u.username,
    avatarUrl: u.avatar_url,
    dailyQuota: u.daily_quota,
    quotaUsed: u.quota_used,
    role: u.role,
  }
}

// ── Auth routes ───────────────────────────────────────────────────────────────

export default async function authRoutes(fastify) {

  // Register
  fastify.post('/auth/register', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req, reply) => {
    const { username, email, password } = req.body
    if (!username || !email || !password) return reply.code(400).send({ error: 'Missing fields' })
    if (!/^[A-Za-z0-9_]{2,32}$/.test(username)) {
      return reply.code(400).send({ error: '用户名需为2-32位字母、数字或下划线' })
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      return reply.code(400).send({ error: '邮箱格式不正确' })
    }
    if (password.length < 8) return reply.code(400).send({ error: '密码至少8位' })

    const { rows: settingRows } = await pool.query(
      `SELECT value FROM system_settings WHERE key='registration_enabled'`
    )
    if (settingRows[0]?.value === false) return reply.code(403).send({ error: '当前暂停新用户注册' })
    const { rows: quotaRows } = await pool.query(
      `SELECT value FROM system_settings WHERE key='default_daily_quota'`
    )
    const defaultQuota = Number(quotaRows[0]?.value ?? 200)

    const hash = await bcrypt.hash(password, 12)
    try {
      const { rows } = await pool.query(
        `INSERT INTO users(username,email,password_hash,daily_quota) VALUES($1,$2,$3,$4) RETURNING *`,
        [username, email.toLowerCase(), hash, defaultQuota]
      )
      const user = rows[0]
      const tokens = makeTokens(fastify, user.id, user.role)
      await storeRefreshToken(user.id, tokens.refreshToken)
      return { ...tokens, user: userToDto(user) }
    } catch (e) {
      if (e.code === '23505') return reply.code(409).send({ error: '用户名或邮箱已存在' })
      throw e
    }
  })

  // Login — support both email and username
  fastify.post('/auth/login', { config: { rateLimit: { max: 15, timeWindow: '1 minute' } } }, async (req, reply) => {
    const { email, password } = req.body
    const { rows } = await pool.query(
      `SELECT * FROM users WHERE (email=$1 OR username=$1) AND is_active=true`,
      [email]
    )
    const user = rows[0]
    if (!user || !await bcrypt.compare(password, user.password_hash)) {
      return reply.code(401).send({ error: '邮箱或密码错误' })
    }
    if (user.is_banned) return reply.code(403).send({ error: '账号已被封禁' })

    // Reset quota if new day
    const now = new Date()
    const resetAt = new Date(user.quota_reset_at)
    if (now.toDateString() !== resetAt.toDateString()) {
      await pool.query(`UPDATE users SET quota_used=0, quota_reset_at=now() WHERE id=$1`, [user.id])
      user.quota_used = 0
    }

    const tokens = makeTokens(fastify, user.id, user.role)
    await storeRefreshToken(user.id, tokens.refreshToken)
    return { ...tokens, user: userToDto(user) }
  })

  // Refresh
  fastify.post('/auth/refresh', async (req, reply) => {
    const { refreshToken } = req.body
    const client = await pool.connect()
    try {
      const payload = jwt.verify(refreshToken, process.env.JWT_REFRESH_SECRET || 'refresh_secret')
      if (payload.type !== 'refresh') return reply.code(401).send({ error: 'Invalid token type' })
      await client.query('BEGIN')
      const { rows: tokenRows } = await client.query(
        `UPDATE refresh_tokens SET revoked_at=now()
         WHERE token_hash=$1 AND user_id=$2 AND revoked_at IS NULL AND expires_at>now()
         RETURNING id`,
        [tokenHash(refreshToken), payload.sub]
      )
      if (!tokenRows[0]) {
        const { rows: existingRows } = await client.query(
          `SELECT 1 FROM refresh_tokens WHERE user_id=$1 LIMIT 1`,
          [payload.sub]
        )
        if (existingRows[0]) {
          await client.query('ROLLBACK')
          return reply.code(401).send({ error: 'Refresh token has already been used or revoked' })
        }
      }
      const { rows } = await client.query(`SELECT * FROM users WHERE id=$1`, [payload.sub])
      if (!rows[0] || !rows[0].is_active || rows[0].is_banned) {
        await client.query('ROLLBACK')
        return reply.code(401).send({ error: 'User is unavailable' })
      }
      const tokens = makeTokens(fastify, payload.sub, rows[0].role)
      await storeRefreshToken(payload.sub, tokens.refreshToken, client)
      await client.query('COMMIT')
      return { ...tokens, user: userToDto(rows[0]) }
    } catch {
      await client.query('ROLLBACK').catch(() => {})
      return reply.code(401).send({ error: 'Token expired or invalid' })
    } finally {
      client.release()
    }
  })

  // Logout
  fastify.post('/auth/logout', { onRequest: [fastify.authenticate] }, async (req) => {
    await pool.query(`UPDATE refresh_tokens SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, [req.user.sub])
    return { success: true }
  })

  fastify.post('/auth/forgot-password', { config: { rateLimit: { max: 5, timeWindow: '15 minutes' } } }, async (req, reply) => {
    const email = String(req.body?.email || '').trim().toLowerCase()
    if (!email) return reply.code(400).send({ error: '请输入邮箱' })
    const { rows } = await pool.query(`SELECT id,email FROM users WHERE email=$1 AND is_active=true`, [email])
    if (!rows[0]) return { success: true }

    const token = crypto.randomBytes(32).toString('hex')
    const tokenHash = crypto.createHash('sha256').update(token).digest('hex')
    await pool.query(
      `INSERT INTO password_reset_tokens(user_id,token_hash,expires_at)
       VALUES($1,$2,now()+interval '30 minutes')`,
      [rows[0].id, tokenHash]
    )
    const emailSent = await sendPasswordResetEmail(rows[0].email, token)
    const response = { success: true, emailSent }
    if (!emailSent && process.env.NODE_ENV !== 'production') response.resetToken = token
    return response
  })

  fastify.post('/auth/reset-password', async (req, reply) => {
    const { token, password } = req.body || {}
    if (!token || typeof password !== 'string' || password.length < 8) {
      return reply.code(400).send({ error: '重置令牌无效或密码少于8位' })
    }
    const tokenHash = crypto.createHash('sha256').update(token).digest('hex')
    const client = await pool.connect()
    try {
      await client.query('BEGIN')
      const { rows } = await client.query(
        `SELECT * FROM password_reset_tokens
         WHERE token_hash=$1 AND used_at IS NULL AND expires_at>now() FOR UPDATE`,
        [tokenHash]
      )
      if (!rows[0]) {
        await client.query('ROLLBACK')
        return reply.code(400).send({ error: '重置链接无效或已过期' })
      }
      const hash = await bcrypt.hash(password, 12)
      await client.query(`UPDATE users SET password_hash=$1,updated_at=now() WHERE id=$2`, [hash, rows[0].user_id])
      await client.query(`UPDATE password_reset_tokens SET used_at=now() WHERE id=$1`, [rows[0].id])
      await client.query('COMMIT')
      return { success: true }
    } catch (error) {
      await client.query('ROLLBACK')
      throw error
    } finally {
      client.release()
    }
  })
}
