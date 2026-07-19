import OpenAI from 'openai'
import { pool } from '../db/client.js'
import { decryptSecret } from './secretVault.js'
import {
  invalidProviderResponse,
  providerFetch,
  providerRequestTimeoutMs,
  withProviderBaseUrlFallback,
} from './providerApi.js'

const EMBEDDING_MODEL = process.env.EMBEDDING_MODEL || 'text-embedding-3-small'

async function embeddingCredentials() {
  const { rows } = await pool.query(
    `SELECT api_key,base_url FROM api_keys
     WHERE provider='openai' AND is_active=true ORDER BY created_at DESC LIMIT 1`
  )
  const row = rows[0]
  const apiKey = row?.api_key ? decryptSecret(row.api_key) : process.env.OPENAI_API_KEY
  if (!apiKey) return null
  return { apiKey, baseUrl: row?.base_url || null }
}

export async function createEmbedding(text) {
  if (!text?.trim()) return null
  try {
    const credentials = await embeddingCredentials()
    if (!credentials) return null
    return await withProviderBaseUrlFallback('openai', credentials.baseUrl, async baseURL => {
      const client = new OpenAI({
        apiKey: credentials.apiKey,
        baseURL,
        timeout: providerRequestTimeoutMs(),
        maxRetries: 1,
        fetch: providerFetch,
      })
      const response = await client.embeddings.create({ model: EMBEDDING_MODEL, input: text.trim() })
      if (!response || typeof response !== 'object' || !Array.isArray(response.data)) {
        throw invalidProviderResponse('向量接口返回了非兼容的 JSON 数据')
      }
      return response.data[0]?.embedding || null
    })
  } catch {
    return null
  }
}

export function cosineSimilarity(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length || left.length === 0) return -1
  let dot = 0
  let leftMagnitude = 0
  let rightMagnitude = 0
  for (let index = 0; index < left.length; index += 1) {
    dot += left[index] * right[index]
    leftMagnitude += left[index] * left[index]
    rightMagnitude += right[index] * right[index]
  }
  if (!leftMagnitude || !rightMagnitude) return -1
  return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude))
}
