import { pool } from '../db/client.js'
import { getClawbotQrCode, getClawbotQrStatus } from '../services/clawbot.js'
import { resetClawbotChannel } from '../services/clawbot.js'
import { decryptSecret, encryptSecret } from '../services/secretVault.js'
import { activeWechatWorkerIds, startWechatWorker, stopWechatWorker } from '../services/wechatWorker.js'
import { buildWechatBindingStatus } from '../services/wechatStatus.js'

async function findAvailablePersona(userId, rawPersonaId, db = pool) {
  const personaId = typeof rawPersonaId === 'string' ? rawPersonaId.trim() : ''
  if (!personaId) return { id: null, name: '默认喵喵' }
  const { rows } = await db.query(
    `SELECT id,name FROM personas
     WHERE id::text=$1 AND (is_builtin=true OR user_id=$2)`,
    [personaId, userId]
  )
  return rows[0] || null
}

export default async function wechatRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }
  const emptyBody = { schema: { body: { type: 'object' } } }

  // Get QR code from Clawbot
  fastify.post('/wechat/qrcode', { ...auth, ...emptyBody }, async (req, reply) => {
    try {
      return await getClawbotQrCode()
    } catch (err) {
      req.log.warn({ err }, 'Failed to request ClawBot QR code')
      return reply.code(502).send({ error: '微信二维码服务暂时不可用，请稍后重试' })
    }
  })

  // Poll QR status
  fastify.post('/wechat/qrcode/status', auth, async (req, reply) => {
    const { qrcode } = req.body
    if (!qrcode) return reply.code(400).send({ error: 'qrcode required' })
    const hasPersonaSelection = Object.prototype.hasOwnProperty.call(req.body || {}, 'personaId')

    let status
    try {
      status = await getClawbotQrStatus(qrcode)
    } catch (err) {
      req.log.warn({ err }, 'Failed to poll ClawBot QR status')
      return reply.code(502).send({ error: '微信二维码状态查询失败，请稍后重试' })
    }

    // On confirmed, save binding
    if (status.status === 'confirmed') {
      const creds = status.credentials
      if (creds?.bot_token) {
        const persona = hasPersonaSelection
          ? await findAvailablePersona(req.user.sub, req.body.personaId)
          : null
        if (hasPersonaSelection && !persona) {
          return reply.code(400).send({ error: '所选人格不存在或不可用' })
        }
        const { rows: bindingRows } = await pool.query(`
          INSERT INTO wechat_bindings (
            user_id,bot_token,ilink_bot_id,ilink_user_id,base_url,persona_id,is_active,worker_status
          )
          VALUES ($1,$2,$3,$4,$5,$6::uuid,true,'stopped')
          ON CONFLICT (user_id) DO UPDATE
            SET bot_token=$2,
                ilink_bot_id=$3,
                ilink_user_id=$4,
                base_url=$5,
                persona_id=CASE WHEN $7::boolean THEN $6::uuid ELSE wechat_bindings.persona_id END,
                is_active=true,
                get_updates_buf='',
                updated_at=now()
          RETURNING id,conversation_id,persona_id
        `, [
          req.user.sub,
          encryptSecret(creds.bot_token),
          creds.ilink_bot_id || '',
          creds.ilink_user_id || '',
          creds.baseurl,
          persona?.id || null,
          hasPersonaSelection,
        ])
        if (bindingRows[0].conversation_id) {
          await pool.query(
            `UPDATE conversations SET persona_id=$1::uuid,updated_at=now()
             WHERE id=$2 AND user_id=$3`,
            [bindingRows[0].persona_id, bindingRows[0].conversation_id, req.user.sub]
          )
        }
        await startWechatWorker(bindingRows[0].id)
      }
    }
    return { data: status }
  })

  // Check current binding status
  fastify.get('/wechat/status', auth, async (req) => {
    const { rows } = await pool.query(
      `SELECT binding.id,binding.ilink_user_id,binding.worker_status,binding.last_error,
              binding.conversation_id,binding.persona_id,
              binding.last_poll_started_at,binding.last_heartbeat_at,
              binding.last_message_at,binding.last_delivery_at,binding.last_error_at,
              binding.next_retry_at,binding.consecutive_failures,
              COALESCE(persona.name,'默认喵喵') AS persona_name,binding.created_at,
              (SELECT count(*)::int FROM messages message
               WHERE message.conversation_id=binding.conversation_id
                 AND message.source='wechat' AND message.role='assistant'
                 AND message.wechat_delivery_status IN ('pending','fallback_pending')) AS pending_delivery_count,
              (SELECT count(*)::int FROM messages message
               WHERE message.conversation_id=binding.conversation_id
                 AND message.source='wechat' AND message.role='assistant'
                 AND message.wechat_delivery_status IN ('failed','fallback_sent')) AS failed_delivery_count
       FROM wechat_bindings binding
       LEFT JOIN personas persona ON persona.id=binding.persona_id
       WHERE binding.user_id=$1 AND binding.is_active=true`,
      [req.user.sub]
    )
    const activeWorkers = new Set(activeWechatWorkerIds())
    return {
      bound: rows.length > 0,
      binding: rows[0]
        ? buildWechatBindingStatus(rows[0], { workerActive: activeWorkers.has(rows[0].id) })
        : null,
      server_time: new Date().toISOString(),
    }
  })

  fastify.post('/wechat/restart', { ...auth, ...emptyBody }, async (req, reply) => {
    const { rows } = await pool.query(
      `SELECT id FROM wechat_bindings WHERE user_id=$1 AND is_active=true`,
      [req.user.sub]
    )
    if (!rows[0]) return reply.code(404).send({ error: '尚未绑定微信' })
    await startWechatWorker(rows[0].id)
    return { success: true }
  })

  fastify.patch('/wechat/persona', auth, async (req, reply) => {
    if (!Object.prototype.hasOwnProperty.call(req.body || {}, 'personaId')) {
      return reply.code(400).send({ error: 'personaId required' })
    }
    const persona = await findAvailablePersona(req.user.sub, req.body.personaId)
    if (!persona) return reply.code(400).send({ error: '所选人格不存在或不可用' })

    const client = await pool.connect()
    try {
      await client.query('BEGIN')
      const { rows } = await client.query(
        `UPDATE wechat_bindings
         SET persona_id=$1::uuid,updated_at=now()
         WHERE user_id=$2 AND is_active=true
         RETURNING id,conversation_id`,
        [persona.id, req.user.sub]
      )
      if (!rows[0]) {
        await client.query('ROLLBACK')
        return reply.code(404).send({ error: '尚未绑定微信' })
      }
      if (rows[0].conversation_id) {
        await client.query(
          `UPDATE conversations SET persona_id=$1::uuid,updated_at=now()
           WHERE id=$2 AND user_id=$3`,
          [persona.id, rows[0].conversation_id, req.user.sub]
        )
      }
      await client.query('COMMIT')
      return { persona_id: persona.id, persona_name: persona.name }
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      throw error
    } finally {
      client.release()
    }
  })

  async function unbind(req) {
    const { rows } = await pool.query(
      `SELECT id,base_url,bot_token FROM wechat_bindings WHERE user_id=$1 AND is_active=true`, [req.user.sub]
    )
    if (rows[0]) {
      await stopWechatWorker(rows[0].id, false)
      let resetError = null
      try {
        await resetClawbotChannel({
          baseUrl: rows[0].base_url,
          botToken: decryptSecret(rows[0].bot_token),
        })
      } catch (error) {
        resetError = `channel reset failed: ${String(error.message || error)}`.slice(0, 1000)
        req.log.warn({ err: error }, 'Failed to notify ClawBot while unbinding')
      }
      await pool.query(
        `UPDATE wechat_bindings SET is_active=false,worker_status='stopped',last_error=$1,updated_at=now() WHERE id=$2`,
        [resetError, rows[0].id]
      )
      return { success: true, channelReset: resetError == null, warning: resetError }
    }
    return { success: true }
  }

  // Both names are kept because the architecture/API docs use channel_reset
  // while released Android clients use unbind.
  fastify.post('/wechat/unbind', { ...auth, ...emptyBody }, unbind)
  fastify.post('/wechat/channel_reset', { ...auth, ...emptyBody }, unbind)
}
