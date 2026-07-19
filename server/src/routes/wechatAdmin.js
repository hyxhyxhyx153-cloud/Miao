import { pool } from '../db/client.js'
import { activeWechatWorkerIds, startWechatWorker, stopWechatWorker } from '../services/wechatWorker.js'
import { recordAudit } from '../services/audit.js'
import { resetClawbotChannel } from '../services/clawbot.js'
import { decryptSecret } from '../services/secretVault.js'

export default async function wechatAdminRoutes(fastify) {
  const adminAuth = { onRequest: [fastify.authenticate, fastify.requireAdmin] }

  fastify.get('/admin/wechat/bindings', adminAuth, async () => {
    const { rows } = await pool.query(
      `SELECT wb.id,wb.user_id,wb.ilink_bot_id,wb.ilink_user_id,wb.base_url,
              wb.worker_status,wb.is_active,wb.last_error,wb.conversation_id,
              wb.created_at,wb.updated_at,u.username
       FROM wechat_bindings wb
       JOIN users u ON u.id = wb.user_id
       WHERE wb.is_active = true
       ORDER BY wb.created_at DESC`
    )
    const activeWorkers = new Set(activeWechatWorkerIds())
    return rows.map(row => ({ ...row, worker_live: activeWorkers.has(row.id) }))
  })

  fastify.post('/admin/wechat/bindings/:id/restart', adminAuth, async (req, reply) => {
    const { rows } = await pool.query(`SELECT id FROM wechat_bindings WHERE id=$1 AND is_active=true`, [req.params.id])
    if (!rows[0]) return reply.code(404).send({ error: 'Not found' })
    await startWechatWorker(rows[0].id)
    await recordAudit(req, 'wechat_worker.restart', 'wechat_binding', rows[0].id)
    return { success: true }
  })

  fastify.delete('/admin/wechat/bindings/:id', adminAuth, async (req, reply) => {
    const { rows } = await pool.query(
      `SELECT id,base_url,bot_token FROM wechat_bindings WHERE id=$1 AND is_active=true`,
      [req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Not found' })
    await stopWechatWorker(rows[0].id, false)
    let resetError = null
    try {
      await resetClawbotChannel({
        baseUrl: rows[0].base_url,
        botToken: decryptSecret(rows[0].bot_token),
      })
    } catch (error) {
      resetError = `channel reset failed: ${String(error.message || error)}`.slice(0, 1000)
      req.log.warn({ err: error }, 'Failed to notify ClawBot during forced unbind')
    }
    await pool.query(
      `UPDATE wechat_bindings SET is_active=false,worker_status='stopped',last_error=$1,updated_at=now() WHERE id=$2`,
      [resetError, rows[0].id]
    )
    await recordAudit(req, 'wechat_binding.disable', 'wechat_binding', rows[0].id)
    return { success: true, channelReset: resetError == null, warning: resetError }
  })
}
