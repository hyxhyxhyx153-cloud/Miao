import { ProxyAgent } from 'undici'

export const PROVIDER_BASE_URLS = Object.freeze({
  anthropic: 'https://api.anthropic.com',
  openai: 'https://api.openai.com/v1',
  'gpt-image': 'https://api.openai.com/v1',
  deepseek: 'https://api.deepseek.com',
  qwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
})

const proxyAgents = new Map()
const resolvedBaseUrlCache = new Map()
const DEFAULT_BASE_URL_CACHE_TTL_MS = 5 * 60_000

function positiveInteger(value, fallback) {
  const parsed = Number.parseInt(value, 10)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

function targetUrl(input) {
  if (input instanceof URL) return input
  if (typeof input === 'string') return new URL(input)
  if (input && typeof input.url === 'string') return new URL(input.url)
  return null
}

function bypassesProxy(url) {
  const rules = String(process.env.NO_PROXY || process.env.no_proxy || '')
    .split(',')
    .map(rule => rule.trim().toLowerCase())
    .filter(Boolean)
  if (!rules.length || !url) return false

  const hostname = url.hostname.toLowerCase()
  const host = url.host.toLowerCase()
  return rules.some(rule => {
    if (rule === '*') return true
    const normalized = rule.replace(/^https?:\/\//, '').replace(/\/$/, '')
    if (normalized.includes(':') && normalized === host) return true
    const domain = normalized.replace(/^\./, '')
    return hostname === domain || hostname.endsWith(`.${domain}`)
  })
}

function proxyFor(input) {
  const url = targetUrl(input)
  if (bypassesProxy(url)) return null
  if (process.env.API_PROXY_URL) return process.env.API_PROXY_URL
  if (url?.protocol === 'http:') {
    return process.env.HTTP_PROXY
      || process.env.http_proxy
      || process.env.ALL_PROXY
      || process.env.all_proxy
      || null
  }
  return process.env.HTTPS_PROXY
    || process.env.https_proxy
    || process.env.HTTP_PROXY
    || process.env.http_proxy
    || process.env.ALL_PROXY
    || process.env.all_proxy
    || null
}

function proxyAgent(proxyUrl) {
  let agent = proxyAgents.get(proxyUrl)
  if (!agent) {
    const parsed = new URL(proxyUrl)
    if (!['http:', 'https:'].includes(parsed.protocol)) {
      throw new Error('API 代理地址仅支持 HTTP 或 HTTPS 协议')
    }
    agent = new ProxyAgent(parsed.toString())
    proxyAgents.set(proxyUrl, agent)
  }
  return agent
}

export async function providerFetch(input, init = {}) {
  const proxyUrl = proxyFor(input)
  if (!proxyUrl) return globalThis.fetch(input, init)
  return globalThis.fetch(input, { ...init, dispatcher: proxyAgent(proxyUrl) })
}

export function providerValidationTimeoutMs() {
  return positiveInteger(process.env.API_VALIDATION_TIMEOUT_MS, 15_000)
}

export function providerRequestTimeoutMs() {
  return positiveInteger(process.env.API_REQUEST_TIMEOUT_MS, 120_000)
}

export function imageRequestTimeoutMs() {
  return positiveInteger(process.env.IMAGE_API_TIMEOUT_MS, 180_000)
}

export function normalizeProviderBaseUrl(provider, value) {
  const defaultUrl = PROVIDER_BASE_URLS[provider]
  if (!defaultUrl) throw new Error('不支持的 API 服务商')

  const rawValue = String(value ?? '').trim()
  if (!rawValue) return defaultUrl
  if (rawValue.length > 512) throw new Error('Base URL 不能超过 512 个字符')
  if (!/^[a-z][a-z\d+.-]*:\/\//i.test(rawValue)) {
    throw new Error('Base URL 必须是包含 http:// 或 https:// 的完整地址')
  }

  let url
  try {
    url = new URL(rawValue)
  } catch {
    throw new Error('Base URL 格式无效，请填写完整的 API 地址')
  }
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error('Base URL 仅支持 HTTP 或 HTTPS 协议')
  }
  if (url.username || url.password) {
    throw new Error('Base URL 不能包含用户名或密码')
  }
  if (url.search || url.hash) {
    throw new Error('Base URL 不能包含查询参数或锚点')
  }

  // A configured Base URL is authoritative. Keep it byte-for-byte after
  // trimming surrounding whitespace: relays may intentionally expose their
  // OpenAI-compatible endpoints at the host root instead of under /v1.
  return rawValue
}

export function normalizeConfiguredBaseUrl(provider, value) {
  if (value == null || String(value).trim() === '') return null
  return normalizeProviderBaseUrl(provider, value)
}

export function providerEndpointUrl(provider, baseUrl, endpointPath) {
  const resolvedBaseUrl = normalizeProviderBaseUrl(provider, baseUrl)
  const path = String(endpointPath || '').trim()
  if (!path || path === '/') return resolvedBaseUrl
  return `${resolvedBaseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

export function invalidProviderResponse(message = 'API 返回的内容不是预期的 JSON 结构') {
  const error = new Error(message)
  error.code = 'API_RESPONSE_INVALID'
  error.status = 200
  return error
}

function isFallbackEligibleError(error) {
  if (!error || error.disableBaseUrlFallback) return false
  if ([404, 405].includes(Number(error.status))) return true
  if (String(error.code || '').toUpperCase() === 'API_RESPONSE_INVALID') return true
  const message = String(error.message || '').toLowerCase()
  if (error.name === 'SyntaxError' && /json|unexpected token|unexpected end/.test(message)) return true
  return Number(error.status) >= 200
    && Number(error.status) < 300
    && /json|unexpected token|unexpected end|response structure/.test(message)
}

function runtimeV1Candidate(baseUrl) {
  const parsed = new URL(baseUrl)
  const path = parsed.pathname.replace(/\/+$/, '')
  if (path.toLowerCase().endsWith('/v1')) return null
  parsed.pathname = `${path}/v1`.replace(/^$/, '/v1')
  return parsed.toString().replace(/\/$/, '')
}

function baseUrlCacheTtlMs() {
  return positiveInteger(process.env.API_BASE_URL_CACHE_TTL_MS, DEFAULT_BASE_URL_CACHE_TTL_MS)
}

export function clearResolvedProviderBaseUrlCache() {
  resolvedBaseUrlCache.clear()
}

export async function withProviderBaseUrlFallback(provider, configuredBaseUrl, operation) {
  const primaryBaseUrl = normalizeProviderBaseUrl(provider, configuredBaseUrl)
  const hasCustomBaseUrl = configuredBaseUrl != null && String(configuredBaseUrl).trim() !== ''
  if (provider === 'anthropic' || !hasCustomBaseUrl) {
    return operation(primaryBaseUrl)
  }

  const cacheKey = `${provider}\u0000${primaryBaseUrl}`
  const cached = resolvedBaseUrlCache.get(cacheKey)
  const cachedBaseUrl = cached?.expiresAt > Date.now() ? cached.baseUrl : null
  if (cached && !cachedBaseUrl) resolvedBaseUrlCache.delete(cacheKey)

  const fallbackBaseUrl = runtimeV1Candidate(primaryBaseUrl)
  const candidates = [...new Set([
    cachedBaseUrl,
    primaryBaseUrl,
    fallbackBaseUrl,
  ].filter(Boolean))]

  let lastError
  for (let index = 0; index < candidates.length; index += 1) {
    const candidate = candidates[index]
    try {
      const result = await operation(candidate)
      resolvedBaseUrlCache.set(cacheKey, {
        baseUrl: candidate,
        expiresAt: Date.now() + baseUrlCacheTtlMs(),
      })
      return result
    } catch (error) {
      lastError = error
      if (index === candidates.length - 1 || !isFallbackEligibleError(error)) throw error
    }
  }
  throw lastError
}

function errorChain(error) {
  const result = []
  let current = error
  for (let depth = 0; current && depth < 5; depth += 1) {
    result.push(current)
    current = current.cause
  }
  return result
}

export function classifyProviderError(error) {
  const chain = errorChain(error)
  const status = chain.map(item => Number(item?.status)).find(Number.isInteger) || null
  const errorCode = chain
    .map(item => String(item?.code || item?.errno || '').toUpperCase())
    .find(Boolean) || ''
  const rawMessage = chain.map(item => String(item?.message || '')).filter(Boolean).join(' | ')
  const message = rawMessage.toLowerCase()

  if (status === 401 || status === 403 || /incorrect api key|invalid api key|authentication|unauthorized/.test(message)) {
    return {
      statusCode: 422,
      code: 'API_KEY_INVALID',
      error: 'API Key 无效或没有访问权限，请检查密钥和服务商',
      retryable: false,
      canSave: false,
    }
  }
  if (status === 404 || status === 405) {
    return {
      statusCode: 502,
      code: 'API_ENDPOINT_NOT_FOUND',
      error: 'API 地址未提供当前操作所需的接口；系统不会自动补 /v1，请按服务商文档填写完整 Base URL',
      retryable: false,
      canSave: true,
    }
  }
  if (errorCode === 'API_RESPONSE_INVALID') {
    return {
      statusCode: 502,
      code: 'API_RESPONSE_INVALID',
      error: 'API 返回了网页或非兼容 JSON，请检查 Base URL；系统已尝试原地址及运行时 /v1 兼容地址',
      retryable: false,
      canSave: true,
    }
  }
  if (status === 429) {
    return {
      statusCode: 429,
      code: 'API_RATE_LIMITED',
      error: '外部 API 当前限流或额度不足，密钥可以先保存后稍后重试',
      retryable: true,
      canSave: true,
    }
  }
  if (errorCode === 'ENOTFOUND' || errorCode === 'EAI_AGAIN' || /getaddrinfo|name resolution/.test(message)) {
    return {
      statusCode: 503,
      code: 'API_DNS_ERROR',
      error: '无法解析外部 API 域名，请检查服务器 DNS、网络或改用可访问的 Base URL',
      retryable: true,
      canSave: true,
    }
  }
  if (errorCode === 'ECONNREFUSED') {
    return {
      statusCode: 503,
      code: 'API_CONNECTION_REFUSED',
      error: '外部 API 拒绝连接，请检查 Base URL、端口及代理设置',
      retryable: true,
      canSave: true,
    }
  }
  if (
    ['ETIMEDOUT', 'UND_ERR_CONNECT_TIMEOUT', 'ABORT_ERR'].includes(errorCode)
    || /timed? ?out|timeout|aborted/.test(message)
  ) {
    return {
      statusCode: 504,
      code: 'API_TIMEOUT',
      error: '连接外部 API 超时，请检查服务器网络、代理或 Base URL；密钥可以先保存',
      retryable: true,
      canSave: true,
    }
  }
  if (
    ['ECONNRESET', 'ENETUNREACH', 'EHOSTUNREACH', 'UND_ERR_SOCKET'].includes(errorCode)
    || /fetch failed|connection error|network/.test(message)
  ) {
    return {
      statusCode: 503,
      code: 'API_NETWORK_ERROR',
      error: '服务器当前无法连接外部 API，请检查网络、DNS、代理或 Base URL；密钥可以先保存',
      retryable: true,
      canSave: true,
    }
  }
  if (status && status >= 500) {
    return {
      statusCode: 502,
      code: 'API_UPSTREAM_ERROR',
      error: `外部 API 暂时不可用（HTTP ${status}），请稍后重试`,
      retryable: true,
      canSave: true,
    }
  }
  if (/base url|invalid url|only valid absolute urls|仅支持 http/.test(message)) {
    return {
      statusCode: 400,
      code: 'API_BASE_URL_INVALID',
      error: rawMessage || 'Base URL 格式无效',
      retryable: false,
      canSave: false,
    }
  }
  return {
    statusCode: 502,
    code: 'API_VALIDATION_FAILED',
    error: rawMessage ? `API 验证失败：${rawMessage.slice(0, 240)}` : 'API 验证失败',
    retryable: false,
    canSave: true,
  }
}

export function providerErrorPayload(error) {
  const result = classifyProviderError(error)
  return {
    error: result.error,
    code: result.code,
    retryable: result.retryable,
    canSave: result.canSave,
  }
}
