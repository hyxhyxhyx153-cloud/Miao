import { pool } from '../db/client.js'
import bcrypt from 'bcrypt'

async function resetDailyQuota(userId) {
  await pool.query(
    `UPDATE users SET quota_used=0,quota_reset_at=now()
     WHERE id=$1 AND quota_reset_at::date<>current_date`,
    [userId]
  )
}

export default async function userRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }

  fastify.get('/user/profile', auth, async (req) => {
    await resetDailyQuota(req.user.sub)
    const { rows } = await pool.query(`SELECT * FROM users WHERE id=$1`, [req.user.sub])
    const u = rows[0]
    return {
      id: u.id, username: u.username, email: u.email,
      nickname: u.nickname, avatarUrl: u.avatar_url,
      dailyQuota: u.daily_quota, quotaUsed: u.quota_used,
    }
  })

  fastify.patch('/user/profile', auth, async (req) => {
    const { nickname, avatarUrl } = req.body
    const { rows } = await pool.query(
      `UPDATE users SET nickname=COALESCE($1,nickname),avatar_url=COALESCE($2,avatar_url),updated_at=now()
       WHERE id=$3 RETURNING *`,
      [nickname, avatarUrl, req.user.sub]
    )
    const u = rows[0]
    return {
      id: u.id, username: u.username, email: u.email, nickname: u.nickname,
      avatarUrl: u.avatar_url, dailyQuota: u.daily_quota, quotaUsed: u.quota_used,
    }
  })

  fastify.get('/user/quota', auth, async (req) => {
    await resetDailyQuota(req.user.sub)
    const [{ rows }, { rows: settingRows }] = await Promise.all([
      pool.query(`SELECT daily_quota,quota_used FROM users WHERE id=$1`, [req.user.sub]),
      pool.query(`SELECT value FROM system_settings WHERE key='quota_exhausted_message'`),
    ])
    return {
      dailyQuota: rows[0].daily_quota,
      quotaUsed: rows[0].quota_used,
      quotaExhaustedMessage: String(settingRows[0]?.value || '今日消息已用完喵，明天再来吧～'),
    }
  })

  fastify.get('/user/settings', auth, async (req) => {
    const { rows } = await pool.query(`SELECT settings,updated_at FROM user_settings WHERE user_id=$1`, [req.user.sub])
    return { settings: rows[0]?.settings || {}, updatedAt: rows[0]?.updated_at?.toISOString() || null }
  })

  fastify.put('/user/settings', auth, async (req, reply) => {
    const settings = req.body?.settings
    if (!settings || typeof settings !== 'object' || Array.isArray(settings)) {
      return reply.code(400).send({ error: 'settings must be an object' })
    }
    const { rows } = await pool.query(
      `INSERT INTO user_settings(user_id,settings) VALUES($1,$2)
       ON CONFLICT(user_id) DO UPDATE SET settings=EXCLUDED.settings,updated_at=now()
       RETURNING settings,updated_at`,
      [req.user.sub, JSON.stringify(settings)]
    )
    return { settings: rows[0].settings, updatedAt: rows[0].updated_at.toISOString() }
  })

  fastify.post('/user/change-password', auth, async (req, reply) => {
    const { currentPassword, newPassword } = req.body || {}
    if (typeof newPassword !== 'string' || newPassword.length < 8) {
      return reply.code(400).send({ error: '新密码至少8位' })
    }
    const { rows } = await pool.query(`SELECT password_hash FROM users WHERE id=$1`, [req.user.sub])
    if (!rows[0] || !await bcrypt.compare(currentPassword || '', rows[0].password_hash)) {
      return reply.code(400).send({ error: '当前密码不正确' })
    }
    const hash = await bcrypt.hash(newPassword, 12)
    await pool.query(`UPDATE users SET password_hash=$1,updated_at=now() WHERE id=$2`, [hash, req.user.sub])
    return { success: true }
  })
}
