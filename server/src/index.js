import 'dotenv/config'
import Fastify from 'fastify'
import cors from '@fastify/cors'
import jwt from '@fastify/jwt'
import multipart from '@fastify/multipart'
import rateLimit from '@fastify/rate-limit'
import { connectDb, pool, redis } from './db/client.js'
import { getProviderEnvName, writeProviderApiKeyToEnv } from './config/apiKeyEnv.js'
import { decryptSecret, encryptSecret, isEncryptedSecret } from './services/secretVault.js'
import authRoutes from './routes/auth.js'
import conversationRoutes from './routes/conversations.js'
import chatRoutes from './routes/chat.js'
import memoryRoutes from './routes/memories.js'
import emojiRoutes, { adminEmojiRoutes } from './routes/emojis.js'
import adminRoutes from './routes/admin.js'
import userRoutes from './routes/users.js'
import personaRoutes from './routes/personas.js'
import wechatAdminRoutes from './routes/wechatAdmin.js'
import logsRoutes from './routes/logs.js'
import mediaRoutes from './routes/media.js'
import wechatRoutes from './routes/wechat.js'
import modelRoutes from './routes/models.js'
import appContentRoutes from './routes/appContent.js'
import { startAllWechatWorkers, stopAllWechatWorkers } from './services/wechatWorker.js'
import { isIpAllowed } from './services/ipAccess.js'

const fastify = Fastify({
  logger: { level: 'info' },
  // Enable only behind a trusted reverse proxy so req.ip can safely enforce
  // the administrative IP whitelist using X-Forwarded-For.
  trustProxy: process.env.TRUST_PROXY === 'true',
})

const wechatWorkersEnabled = String(process.env.START_WECHAT_WORKERS || 'true').toLowerCase() !== 'false'

if (process.env.NODE_ENV === 'production' && (!process.env.JWT_SECRET || !process.env.JWT_REFRESH_SECRET || !process.env.DATA_ENCRYPTION_KEY)) {
  throw new Error('JWT_SECRET, JWT_REFRESH_SECRET and DATA_ENCRYPTION_KEY are required in production')
}

// ── Plugins ───────────────────────────────────────────────────────────────────

const allowedOrigins = String(process.env.CORS_ORIGINS || '').split(',').map(value => value.trim()).filter(Boolean)
await fastify.register(cors, {
  origin: allowedOrigins.length
    ? (origin, callback) => callback(null, !origin || allowedOrigins.includes(origin))
    : true,
})
await fastify.register(rateLimit, { max: 300, timeWindow: '1 minute' })
await fastify.register(multipart, {
  limits: { fileSize: 20 * 1024 * 1024, files: 20 },
})

await fastify.register(jwt, {
  secret: process.env.JWT_SECRET || 'dev_secret_change_me',
  sign: { algorithm: 'HS256' },
})

// ── Decorators ────────────────────────────────────────────────────────────────

fastify.decorate('authenticate', async function (req, reply) {
  try {
    await req.jwtVerify()
    const { rows } = await pool.query(
      `SELECT is_active,is_banned,role FROM users WHERE id=$1`,
      [req.user.sub]
    )
    const user = rows[0]
    if (!user || !user.is_active || user.is_banned) {
      return reply.code(401).send({ error: 'Account is unavailable' })
    }
    // The database is authoritative if an account's role changes while an
    // access token is still valid.
    req.user.role = user.role
  } catch {
    if (!reply.sent) return reply.code(401).send({ error: 'Unauthorized' })
  }
})

fastify.decorate('requireAdmin', async function (req, reply) {
  if (req.user?.role !== 'admin') {
    return reply.code(403).send({ error: 'Forbidden' })
  }
  const { rows } = await pool.query(`SELECT value FROM system_settings WHERE key='ip_whitelist'`)
  const whitelist = rows[0]?.value
  if (!isIpAllowed(req.ip, whitelist)) {
    return reply.code(403).send({ error: 'Admin access is not allowed from this IP' })
  }
})

// ── Routes ────────────────────────────────────────────────────────────────────

const PREFIX = '/api/v1'

await fastify.register(authRoutes, { prefix: PREFIX })
await fastify.register(conversationRoutes, { prefix: PREFIX })
await fastify.register(chatRoutes, { prefix: PREFIX })
await fastify.register(memoryRoutes, { prefix: PREFIX })
await fastify.register(emojiRoutes, { prefix: PREFIX })
await fastify.register(adminEmojiRoutes, { prefix: PREFIX })
await fastify.register(adminRoutes, { prefix: PREFIX })
await fastify.register(userRoutes, { prefix: PREFIX })
await fastify.register(personaRoutes, { prefix: PREFIX })
await fastify.register(wechatAdminRoutes, { prefix: PREFIX })
await fastify.register(wechatRoutes, { prefix: PREFIX })
await fastify.register(logsRoutes, { prefix: PREFIX })
await fastify.register(mediaRoutes, { prefix: PREFIX })
await fastify.register(modelRoutes, { prefix: PREFIX })
await fastify.register(appContentRoutes, { prefix: PREFIX })

fastify.addHook('onClose', async () => {
  await stopAllWechatWorkers()
  if (redis.isOpen) await redis.quit()
  await pool.end()
})

let shuttingDown = false
async function shutdown(signal) {
  if (shuttingDown) return
  shuttingDown = true
  fastify.log.info({ signal }, 'Graceful shutdown started')
  const forceExit = setTimeout(() => {
    fastify.log.error('Graceful shutdown timed out')
    process.exit(1)
  }, 15_000)
  forceExit.unref?.()
  try {
    await fastify.close()
    process.exitCode = 0
  } catch (error) {
    fastify.log.error(error, 'Graceful shutdown failed')
    process.exitCode = 1
  } finally {
    clearTimeout(forceExit)
  }
}

process.once('SIGTERM', () => { shutdown('SIGTERM') })
process.once('SIGINT', () => { shutdown('SIGINT') })

// Health check
fastify.get('/health', () => ({ status: 'ok', time: new Date().toISOString() }))

// Root
fastify.get('/', () => ({
  name: '喵 Miao API Server',
  version: '1.0.0',
  status: 'running',
  docs: '/health — 健康检查',
  api: '/api/v1/',
}))

// ── Start ─────────────────────────────────────────────────────────────────────

try {
  await connectDb()
  const { rows: legacyApiKeys } = await pool.query(`SELECT id,api_key FROM api_keys`)
  for (const row of legacyApiKeys) {
    if (row.api_key && !isEncryptedSecret(row.api_key)) {
      await pool.query(`UPDATE api_keys SET api_key=$1,updated_at=now() WHERE id=$2`, [encryptSecret(row.api_key), row.id])
    }
  }
  const { rows: legacyWechatTokens } = await pool.query(`SELECT id,bot_token FROM wechat_bindings`)
  for (const row of legacyWechatTokens) {
    if (row.bot_token && !isEncryptedSecret(row.bot_token)) {
      await pool.query(`UPDATE wechat_bindings SET bot_token=$1,updated_at=now() WHERE id=$2`, [encryptSecret(row.bot_token), row.id])
    }
  }
  try {
    const { rows } = await pool.query(
      `SELECT DISTINCT ON (provider) provider, api_key
       FROM api_keys
       WHERE is_active=true
       ORDER BY provider, created_at DESC`
    )
    for (const row of rows) {
      if (getProviderEnvName(row.provider)) {
        await writeProviderApiKeyToEnv(row.provider, decryptSecret(row.api_key))
      }
    }
  } catch (err) {
    fastify.log.error(err, 'Failed to sync stored API keys to the environment file')
  }
  if (wechatWorkersEnabled) {
    await startAllWechatWorkers()
  } else {
    fastify.log.info('WeChat workers are disabled by START_WECHAT_WORKERS=false')
  }
  await fastify.listen({ port: process.env.PORT || 3000, host: '0.0.0.0' })
  console.log(`[Server] Running on http://0.0.0.0:${process.env.PORT || 3000}`)
} catch (err) {
  fastify.log.error(err)
  process.exit(1)
}
