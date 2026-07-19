import { pool } from '../db/client.js'
import { redis } from '../db/client.js'
import { sendApiKeyUsageAlert } from '../services/mailer.js'

// ── Migration ─────────────────────────────────────────────────────────────────
await pool.query(`
  CREATE TABLE IF NOT EXISTS request_logs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID,
    username          VARCHAR(64),
    user_email        VARCHAR(255),
    conversation_id   UUID,
    conversation_title VARCHAR(128),
    model_provider    VARCHAR(32),
    model_id          VARCHAR(64),
    api_key_id        UUID,
    request_content   TEXT,
    response_content  TEXT,
    input_tokens      INT DEFAULT 0,
    output_tokens     INT DEFAULT 0,
    duration_ms       INT DEFAULT 0,
    status            VARCHAR(16) DEFAULT 'ok',
    error_msg         TEXT,
    created_at        TIMESTAMPTZ DEFAULT now()
  )
`).catch(err => console.error('[logs] Migration failed:', err))

// ALTER for existing tables (safe: IF NOT EXISTS)
await pool.query(`
  ALTER TABLE request_logs
    ADD COLUMN IF NOT EXISTS username VARCHAR(64),
    ADD COLUMN IF NOT EXISTS user_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS request_content TEXT,
    ADD COLUMN IF NOT EXISTS response_content TEXT,
    ADD COLUMN IF NOT EXISTS conversation_title VARCHAR(128),
    ADD COLUMN IF NOT EXISTS api_key_id UUID
`).catch(() => {})

await pool.query(`ALTER TABLE request_logs ALTER COLUMN model_id TYPE VARCHAR(128)`).catch(() => {})
await pool.query(`CREATE INDEX IF NOT EXISTS idx_request_logs_created_at ON request_logs(created_at DESC)`).catch(() => {})
await pool.query(`DELETE FROM request_logs WHERE created_at < now() - interval '7 days'`).catch(() => {})

let lastRetentionCleanup = Date.now()

async function cleanupExpiredLogs() {
  if (Date.now() - lastRetentionCleanup < 60 * 60 * 1000) return
  lastRetentionCleanup = Date.now()
  await pool.query(`DELETE FROM request_logs WHERE created_at < now() - interval '7 days'`).catch(() => {})
}

// ── logRequest ────────────────────────────────────────────────────────────────
export async function logRequest(data) {
  try {
    await cleanupExpiredLogs()
    const {
      userId = null,
      username = null,
      userEmail = null,
      conversationId = null,
      conversationTitle = null,
      modelProvider = null,
      modelId = null,
      apiKeyId = null,
      requestContent = null,
      responseContent = null,
      inputTokens = 0,
      outputTokens = 0,
      durationMs = 0,
      status = 'ok',
      errorMsg = null,
    } = data

    await pool.query(
      `INSERT INTO request_logs
         (user_id, username, user_email, conversation_id, conversation_title,
          model_provider, model_id, api_key_id, request_content, response_content,
          input_tokens, output_tokens, duration_ms, status, error_msg)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)`,
      [userId, username, userEmail, conversationId, conversationTitle,
       modelProvider, modelId, apiKeyId,
       requestContent ? requestContent.slice(0, 2000) : null,
       responseContent ? responseContent.slice(0, 4000) : null,
       inputTokens, outputTokens, durationMs, status, errorMsg]
    )
    if (apiKeyId) await maybeSendApiKeyAlert(apiKeyId)
  } catch {
    // fire-and-forget
  }
}

async function maybeSendApiKeyAlert(apiKeyId) {
  const { rows } = await pool.query(
    `SELECT k.provider,k.alert_threshold,
      COALESCE(SUM(l.input_tokens+l.output_tokens) FILTER (WHERE l.created_at>=current_date),0)::bigint AS today_tokens
     FROM api_keys k LEFT JOIN request_logs l ON l.api_key_id=k.id
     WHERE k.id=$1 GROUP BY k.id`,
    [apiKeyId]
  )
  const key = rows[0]
  if (!key?.alert_threshold || Number(key.today_tokens) < Number(key.alert_threshold)) return
  const date = new Date().toISOString().slice(0, 10)
  if (redis.isOpen) {
    const acquired = await redis.set(`api-key-alert:${apiKeyId}:${date}`, '1', { NX: true, EX: 172800 })
    if (acquired !== 'OK') return
  }
  const { rows: admins } = await pool.query(
    `SELECT email FROM users WHERE role='admin' AND is_active=true AND is_banned=false`
  )
  await sendApiKeyUsageAlert(admins.map(admin => admin.email), {
    provider: key.provider,
    todayTokens: Number(key.today_tokens),
    threshold: Number(key.alert_threshold),
  })
}

// ── Routes ────────────────────────────────────────────────────────────────────
export default async function logsRoutes(fastify) {
  const adminAuth = { onRequest: [fastify.authenticate, fastify.requireAdmin] }

  // List
  fastify.get('/admin/logs', adminAuth, async (req) => {
    const limit  = Math.min(parseInt(req.query.limit)  || 50, 500)
    const offset = parseInt(req.query.offset) || 0
    const status = req.query.status || null      // filter by 'ok' or 'error'
    const search = req.query.search || null      // filter by username/email

    const conditions = [`created_at >= now() - interval '7 days'`]
    const params = []
    let p = 1

    if (status) { conditions.push(`status=$${p++}`); params.push(status) }
    if (search) {
      conditions.push(`(username ILIKE $${p} OR user_email ILIKE $${p})`)
      params.push(`%${search}%`); p++
    }

    const where = conditions.join(' AND ')

    const { rows } = await pool.query(
      `SELECT id, user_id, username, user_email,
              conversation_id, conversation_title,
              model_provider, model_id,
              input_tokens, output_tokens, duration_ms,
              status, error_msg, created_at
       FROM request_logs
       WHERE ${where}
       ORDER BY created_at DESC LIMIT $${p} OFFSET $${p+1}`,
      [...params, limit, offset]
    )
    const { rows: cnt } = await pool.query(
      `SELECT COUNT(*) FROM request_logs WHERE ${where}`, params
    )
    return { total: parseInt(cnt[0].count), limit, offset, items: rows }
  })

  // Detail (includes full request/response content)
  fastify.get('/admin/logs/:id', adminAuth, async (req, reply) => {
    const { rows } = await pool.query(
      `SELECT * FROM request_logs WHERE id=$1 AND created_at >= now() - interval '7 days'`, [req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Not found' })
    return rows[0]
  })
}
