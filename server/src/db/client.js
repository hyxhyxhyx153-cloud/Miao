import 'dotenv/config'
import pg from 'pg'
import { createClient } from 'redis'

const { Pool } = pg

export const pool = new Pool({ connectionString: process.env.DATABASE_URL })

export const redis = createClient({ url: process.env.REDIS_URL })
redis.on('error', err => console.error('[Redis]', err))

export async function connectDb() {
  const client = await pool.connect()
  client.release()
  if (!redis.isOpen) await redis.connect()
  console.log('[DB] PostgreSQL + Redis connected')
}
