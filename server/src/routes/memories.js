import { pool } from '../db/client.js'
import { createEmbedding, cosineSimilarity } from '../services/embeddings.js'

export default async function memoryRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }

  fastify.get('/memories', auth, async (req) => {
    const search = String(req.query.search || '').trim()
    const { rows } = await pool.query(
      `SELECT * FROM memories
       WHERE user_id=$1 AND is_active=true AND ($2::text='' OR content ILIKE '%'||$2||'%' OR summary ILIKE '%'||$2||'%')
       ORDER BY created_at DESC`,
      [req.user.sub, search]
    )
    return rows.map(memToDto)
  })

  fastify.post('/memories', auth, async (req, reply) => {
    const { content } = req.body
    if (!content?.trim()) return reply.code(400).send({ error: '记忆内容不能为空' })
    const summary = content.slice(0, 30) + (content.length > 30 ? '...' : '')
    const embedding = await createEmbedding(content)
    const { rows } = await pool.query(
      `INSERT INTO memories(user_id,content,summary,embedding) VALUES($1,$2,$3,$4) RETURNING *`,
      [req.user.sub, content, summary, embedding ? JSON.stringify(embedding) : null]
    )
    return memToDto(rows[0])
  })

  fastify.patch('/memories/:id', auth, async (req, reply) => {
    const { content } = req.body
    if (!content?.trim()) return reply.code(400).send({ error: '记忆内容不能为空' })
    const summary = content.slice(0, 30) + (content.length > 30 ? '...' : '')
    const embedding = await createEmbedding(content)
    const { rows } = await pool.query(
      `UPDATE memories SET content=$1,summary=$2,embedding=$3,updated_at=now()
       WHERE id=$4 AND user_id=$5 RETURNING *`,
      [content, summary, embedding ? JSON.stringify(embedding) : null, req.params.id, req.user.sub]
    )
    if (!rows[0]) return reply.code(404).send({ error: 'Not found' })
    return memToDto(rows[0])
  })

  fastify.delete('/memories/:id', auth, async (req) => {
    await pool.query(
      `UPDATE memories SET is_active=false WHERE id=$1 AND user_id=$2`,
      [req.params.id, req.user.sub]
    )
    return { success: true }
  })

  fastify.get('/memories/semantic-search', auth, async (req, reply) => {
    const query = String(req.query.q || '').trim()
    if (!query) return reply.code(400).send({ error: 'q is required' })
    const queryEmbedding = await createEmbedding(query)
    const { rows } = await pool.query(
      `SELECT * FROM memories WHERE user_id=$1 AND is_active=true`,
      [req.user.sub]
    )
    const ranked = rows
      .map(row => ({
        row,
        score: queryEmbedding && row.embedding
          ? cosineSimilarity(queryEmbedding, row.embedding)
          : lexicalScore(query, row.content),
      }))
      .sort((left, right) => right.score - left.score)
      .slice(0, Math.min(Number(req.query.limit || 10), 20))
    return ranked.map(item => ({ ...memToDto(item.row), relevance: item.score }))
  })
}

function lexicalScore(query, content) {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return 0
  const source = content.toLowerCase()
  return words.filter(word => source.includes(word)).length / words.length
}

function memToDto(r) {
  return {
    id: r.id,
    content: r.content,
    summary: r.summary,
    source: r.source,
    isActive: r.is_active,
    createdAt: r.created_at?.getTime?.() || 0,
    updatedAt: r.updated_at?.getTime?.() || 0,
  }
}
