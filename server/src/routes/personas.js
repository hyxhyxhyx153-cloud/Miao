import { pool } from '../db/client.js'
import { validateReferenceImageUrls } from '../services/imageGeneration.js'
import { ensurePersonaEmotionPrompt } from '../services/personaPrompt.js'

function personaToDto(persona) {
  return {
    id: persona.id,
    name: persona.name,
    description: persona.description,
    systemPrompt: persona.system_prompt,
    referenceImageUrls: persona.reference_image_urls || [],
    isBuiltin: persona.is_builtin,
    sourcePersonaId: persona.source_persona_id || null,
    createdAt: persona.created_at?.toISOString?.() || persona.created_at,
    updatedAt: persona.updated_at?.toISOString?.() || persona.updated_at,
  }
}

export default async function personaRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }
  // User-facing: list own personas and builtins that the user has not overridden.
  fastify.get('/personas', auth, async (req) => {
    const { rows } = await pool.query(
      `SELECT p.* FROM personas p
       WHERE p.user_id=$1 OR (
         p.is_builtin=true AND NOT EXISTS(
           SELECT 1 FROM personas own
           WHERE own.user_id=$1 AND own.source_persona_id=p.id
         )
       )
       ORDER BY p.is_builtin DESC, p.created_at ASC`,
      [req.user.sub]
    )
    return rows.map(personaToDto)
  })

  // Create custom persona
  fastify.post('/personas', auth, async (req, reply) => {
    const { name, description, systemPrompt, system_prompt, referenceImageUrls, reference_image_urls } = req.body || {}
    if (!name?.trim() || !(systemPrompt ?? system_prompt)?.trim()) {
      return reply.code(400).send({ error: 'name and systemPrompt are required' })
    }
    let references
    try {
      references = validateReferenceImageUrls(referenceImageUrls ?? reference_image_urls)
    } catch (error) {
      return reply.code(400).send({ error: error.message })
    }
    const { rows } = await pool.query(
      `INSERT INTO personas(user_id,name,description,system_prompt,reference_image_urls)
       VALUES($1,$2,$3,$4,$5) RETURNING *`,
      [
        req.user.sub,
        name.trim(),
        description || '',
        ensurePersonaEmotionPrompt(systemPrompt ?? system_prompt),
        references,
      ]
    )
    return personaToDto(rows[0])
  })

  // Updating a builtin creates or updates a private override. This prevents a
  // normal user from changing the global persona for every other account.
  fastify.patch('/personas/:id', auth, async (req, reply) => {
    const { name, description, systemPrompt, system_prompt } = req.body || {}
    const prompt = systemPrompt ?? system_prompt
    const hasDescription = Object.prototype.hasOwnProperty.call(req.body || {}, 'description')
    if (name !== undefined && (typeof name !== 'string' || !name.trim())) {
      return reply.code(400).send({ error: 'name cannot be empty' })
    }
    if (prompt !== undefined && (typeof prompt !== 'string' || !prompt.trim())) {
      return reply.code(400).send({ error: 'systemPrompt cannot be empty' })
    }
    if (description !== undefined && description !== null && typeof description !== 'string') {
      return reply.code(400).send({ error: 'description must be a string' })
    }
    const hasReferenceImages = Object.prototype.hasOwnProperty.call(req.body || {}, 'referenceImageUrls')
      || Object.prototype.hasOwnProperty.call(req.body || {}, 'reference_image_urls')
    let references = null
    if (hasReferenceImages) {
      try {
        references = validateReferenceImageUrls(req.body.referenceImageUrls ?? req.body.reference_image_urls)
      } catch (error) {
        return reply.code(400).send({ error: error.message })
      }
    }
    const client = await pool.connect()
    try {
      await client.query('BEGIN')
      const { rows: targetRows } = await client.query(
        `SELECT * FROM personas
         WHERE id=$1 AND (is_builtin=true OR user_id=$2)
         FOR UPDATE`,
        [req.params.id, req.user.sub]
      )
      const target = targetRows[0]
      if (!target) {
        await client.query('ROLLBACK')
        return reply.code(404).send({ error: 'Not found' })
      }

      let updated
      if (target.is_builtin) {
        const { rows: existingRows } = await client.query(
          `SELECT * FROM personas
           WHERE user_id=$1 AND source_persona_id=$2
           FOR UPDATE`,
          [req.user.sub, target.id]
        )
        const baseline = existingRows[0] || target
        const { rows } = await client.query(
          `INSERT INTO personas(
             user_id,source_persona_id,name,description,system_prompt,
             reference_image_urls,is_builtin
           )
           VALUES($1,$2,$3,$4,$5,$6,false)
           ON CONFLICT(user_id,source_persona_id) WHERE source_persona_id IS NOT NULL
           DO UPDATE SET
             name=EXCLUDED.name,
             description=EXCLUDED.description,
             system_prompt=EXCLUDED.system_prompt,
             reference_image_urls=EXCLUDED.reference_image_urls,
             updated_at=now()
           RETURNING *`,
          [
            req.user.sub,
            target.id,
            name === undefined ? baseline.name : name.trim(),
            hasDescription ? (description ?? '') : baseline.description,
            ensurePersonaEmotionPrompt(prompt === undefined ? baseline.system_prompt : prompt),
            hasReferenceImages ? references : baseline.reference_image_urls,
          ]
        )
        updated = rows[0]
        await client.query(
          `UPDATE conversations SET persona_id=$1,updated_at=now()
           WHERE user_id=$2 AND persona_id=$3`,
          [updated.id, req.user.sub, target.id]
        )
        await client.query(
          `UPDATE wechat_bindings SET persona_id=$1,updated_at=now()
           WHERE user_id=$2 AND persona_id=$3`,
          [updated.id, req.user.sub, target.id]
        )
      } else {
        const { rows } = await client.query(
          `UPDATE personas SET
             name = CASE WHEN $1::boolean THEN $2 ELSE name END,
             description = CASE WHEN $3::boolean THEN $4 ELSE description END,
             system_prompt = CASE WHEN $5::boolean THEN $6 ELSE system_prompt END,
             reference_image_urls = CASE WHEN $7::boolean THEN $8::text[] ELSE reference_image_urls END,
             updated_at = now()
           WHERE id=$9 AND user_id=$10 RETURNING *`,
          [
            name !== undefined, name === undefined ? null : name.trim(),
            hasDescription, description ?? '',
            prompt !== undefined, prompt === undefined ? null : ensurePersonaEmotionPrompt(prompt),
            hasReferenceImages, references,
            target.id, req.user.sub,
          ]
        )
        updated = rows[0]
      }

      await client.query('COMMIT')
      return personaToDto(updated)
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      throw error
    } finally {
      client.release()
    }
  })

  // Delete (own only, not builtin)
  fastify.delete('/personas/:id', auth, async (req, reply) => {
    const client = await pool.connect()
    try {
      await client.query('BEGIN')
      const { rows } = await client.query(
        `SELECT source_persona_id FROM personas
         WHERE id=$1 AND user_id=$2 AND is_builtin=false
         FOR UPDATE`,
        [req.params.id, req.user.sub]
      )
      if (!rows[0]) {
        await client.query('ROLLBACK')
        return reply.code(404).send({ error: 'Not found or builtin' })
      }
      if (rows[0].source_persona_id) {
        await client.query(
          `UPDATE conversations SET persona_id=$1,updated_at=now()
           WHERE user_id=$2 AND persona_id=$3`,
          [rows[0].source_persona_id, req.user.sub, req.params.id]
        )
        await client.query(
          `UPDATE wechat_bindings SET persona_id=$1,updated_at=now()
           WHERE user_id=$2 AND persona_id=$3`,
          [rows[0].source_persona_id, req.user.sub, req.params.id]
        )
      }
      await client.query(`DELETE FROM personas WHERE id=$1`, [req.params.id])
      await client.query('COMMIT')
      return { success: true }
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      throw error
    } finally {
      client.release()
    }
  })
}
