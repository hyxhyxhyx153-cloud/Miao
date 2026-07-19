import { pool } from '../db/client.js'
import { recordAudit } from '../services/audit.js'

export default async function emojiRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }

  // Sync (incremental)
  fastify.get('/emojis/sync', auth, async (req) => {
    const since = Number(req.query.since || 0)
    const { rows } = await pool.query(
      `SELECT * FROM emojis WHERE is_active=true AND extract(epoch from created_at)*1000 > $1 ORDER BY created_at ASC`,
      [since]
    )
    return { emojis: rows.map(emojiToDto), hasMore: false }
  })

  fastify.get('/emojis/emotions', auth, async () => {
    const { rows } = await pool.query(`SELECT DISTINCT emotion_tag FROM emojis WHERE is_active=true ORDER BY emotion_tag`)
    return rows.map(r => r.emotion_tag)
  })
}

// ── Admin emoji management ────────────────────────────────────────────────────

export async function adminEmojiRoutes(fastify) {
  const adminAuth = { onRequest: [fastify.authenticate, fastify.requireAdmin] }

  fastify.get('/admin/emojis', adminAuth, async (req) => {
    const { emotion_tag, limit = 50, offset = 0 } = req.query
    const { rows } = await pool.query(
      `SELECT * FROM emojis
       WHERE ($1::text IS NULL OR emotion_tag=$1)
       ORDER BY created_at DESC LIMIT $2 OFFSET $3`,
      [emotion_tag || null, limit, offset]
    )
    return rows.map(emojiToDto)
  })

  fastify.post('/admin/emojis', adminAuth, async (req) => {
    const { filename, emotionTag, description, sceneKeywords, url, thumbUrl } = req.body
    const { rows } = await pool.query(
      `INSERT INTO emojis(filename,emotion_tag,description,scene_keywords,url,thumb_url)
       VALUES($1,$2,$3,$4,$5,$6) RETURNING *`,
      [filename, emotionTag, description || '', sceneKeywords || [], url, thumbUrl || url]
    )
    return emojiToDto(rows[0])
  })

  fastify.patch('/admin/emojis/:id', adminAuth, async (req, reply) => {
    const { emotionTag, description, sceneKeywords, thumbUrl, isActive } = req.body
    const { rows } = await pool.query(
      `UPDATE emojis SET
         emotion_tag = COALESCE($1, emotion_tag),
         description = COALESCE($2, description),
         scene_keywords = COALESCE($3, scene_keywords),
         thumb_url = COALESCE($4, thumb_url),
         is_active = COALESCE($5, is_active)
       WHERE id=$6 RETURNING *`,
      [emotionTag, description, sceneKeywords, thumbUrl, isActive, req.params.id]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Not found' })
    return emojiToDto(rows[0])
  })

  fastify.delete('/admin/emojis/:id', adminAuth, async (req) => {
    await pool.query(`DELETE FROM emojis WHERE id=$1`, [req.params.id])
    await recordAudit(req, 'emoji.delete', 'emoji', req.params.id)
    return { success: true }
  })

  fastify.get('/admin/emojis/stats', adminAuth, async () => {
    const { rows } = await pool.query(
      `SELECT emotion_tag,COUNT(*)::int AS count,SUM(send_count)::int AS send_count,
       COUNT(*) FILTER(WHERE is_active=true)::int AS active_count
       FROM emojis GROUP BY emotion_tag ORDER BY emotion_tag`
    )
    return rows
  })

  fastify.post('/admin/emojis/import-jobs', adminAuth, async (req, reply) => {
    const items = Array.isArray(req.body?.items) ? req.body.items : []
    if (!items.length) return reply.code(400).send({ error: 'items are required' })
    const { rows } = await pool.query(
      `INSERT INTO emoji_import_jobs(admin_user_id,status,total_count,items)
       VALUES($1,'running',$2,$3) RETURNING *`,
      [req.user.sub, items.length, JSON.stringify(items)]
    )
    const job = rows[0]
    let successCount = 0
    const results = []
    for (const item of items) {
      try {
        const { rows: emojiRows } = await pool.query(
          `INSERT INTO emojis(filename,emotion_tag,description,scene_keywords,url,thumb_url)
           VALUES($1,$2,$3,$4,$5,$6) RETURNING *`,
          [
            item.filename,
            item.emotionTag,
            item.description || '',
            item.sceneKeywords || [],
            item.url,
            item.thumbUrl || item.url,
          ]
        )
        successCount += 1
        results.push({ filename: item.filename, status: 'success', emojiId: emojiRows[0].id })
      } catch (error) {
        results.push({ filename: item.filename, status: 'failed', error: String(error.message || error) })
      }
    }
    const { rows: completedRows } = await pool.query(
      `UPDATE emoji_import_jobs SET status=$1,success_count=$2,failed_count=$3,items=$4,updated_at=now()
       WHERE id=$5 RETURNING *`,
      [successCount === items.length ? 'completed' : 'completed_with_errors', successCount, items.length - successCount, JSON.stringify(results), job.id]
    )
    await recordAudit(req, 'emoji.import', 'emoji_import_job', job.id, { total: items.length, successCount })
    return completedRows[0]
  })

  fastify.get('/admin/emojis/import-jobs', adminAuth, async () => {
    const { rows } = await pool.query(`SELECT * FROM emoji_import_jobs ORDER BY created_at DESC LIMIT 100`)
    return rows
  })
}

function emojiToDto(r) {
  return {
    id: r.id,
    filename: r.filename,
    emotionTag: r.emotion_tag,
    description: r.description,
    sceneKeywords: r.scene_keywords || [],
    url: r.url,
    thumbUrl: r.thumb_url || r.url,
    sendCount: r.send_count || 0,
    isActive: r.is_active,
  }
}
