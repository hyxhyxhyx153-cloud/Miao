import { pool } from '../db/client.js'

export default async function modelRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }

  fastify.get('/models', auth, async (req) => {
    const includeDisabled = String(req.query?.includeDisabled || '').toLowerCase() === 'true'
    const { rows } = await pool.query(
      `SELECT model_id,provider,display_name,supports_vision,description,is_enabled
       FROM model_configs
       WHERE ($1::boolean=true OR is_enabled=true)
       ORDER BY is_enabled DESC,provider,model_id`,
      [includeDisabled]
    )
    return rows.map(row => ({
      modelId: row.model_id,
      provider: row.provider,
      displayName: row.display_name || row.model_id,
      supportsVision: row.supports_vision,
      description: row.description || '',
      isEnabled: row.is_enabled,
    }))
  })
}
