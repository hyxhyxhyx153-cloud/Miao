import { afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import OpenAI from 'openai'
import { listProviderModels } from '../routes/admin.js'
import { streamWithCredential } from '../routes/chat.js'
import {
  classifyProviderError,
  clearResolvedProviderBaseUrlCache,
  invalidProviderResponse,
  normalizeConfiguredBaseUrl,
  normalizeProviderBaseUrl,
  providerEndpointUrl,
  providerValidationTimeoutMs,
  withProviderBaseUrlFallback,
} from '../services/providerApi.js'

const originalValidationTimeout = process.env.API_VALIDATION_TIMEOUT_MS
const originalFetch = globalThis.fetch

afterEach(() => {
  clearResolvedProviderBaseUrlCache()
  globalThis.fetch = originalFetch
  if (originalValidationTimeout == null) delete process.env.API_VALIDATION_TIMEOUT_MS
  else process.env.API_VALIDATION_TIMEOUT_MS = originalValidationTimeout
})

test('uses defaults only for empty Base URLs and preserves configured URLs exactly', () => {
  assert.equal(normalizeProviderBaseUrl('openai', ''), 'https://api.openai.com/v1')
  assert.equal(
    normalizeProviderBaseUrl('openai', '  https://gateway.example.com/  '),
    'https://gateway.example.com/'
  )
  assert.equal(normalizeProviderBaseUrl('openai', 'https://api.openai.com'), 'https://api.openai.com')
  assert.throws(
    () => normalizeProviderBaseUrl('openai', 'gateway.example.com/v1/chat/completions'),
    /http:\/\/ 或 https:\/\//
  )
  assert.equal(
    normalizeProviderBaseUrl('gpt-image', 'https://gateway.example.com/openai/v1/images/generations/'),
    'https://gateway.example.com/openai/v1/images/generations/'
  )
  assert.equal(normalizeConfiguredBaseUrl('openai', 'https://gateway.example.com/custom/path/'), 'https://gateway.example.com/custom/path/')
  assert.equal(normalizeConfiguredBaseUrl('openai', '  '), null)
  assert.throws(
    () => normalizeProviderBaseUrl('openai', 'file:///tmp/api'),
    /HTTP 或 HTTPS/
  )
  assert.throws(
    () => normalizeProviderBaseUrl('openai', 'https://gateway.example.com/v1?tenant=one'),
    /查询参数或锚点/
  )
})

test('joins required image endpoints without changing the configured Base URL', () => {
  const rootBaseUrl = 'https://gateway.example.com/'
  assert.equal(providerEndpointUrl('gpt-image', rootBaseUrl, '/images/generations'), 'https://gateway.example.com/images/generations')
  assert.equal(providerEndpointUrl('gpt-image', 'https://gateway.example.com/openai/v1', '/images/edits'), 'https://gateway.example.com/openai/v1/images/edits')
  assert.equal(rootBaseUrl, 'https://gateway.example.com/')
})

test('OpenAI SDK appends protocol endpoints to a root Base URL without adding /v1', async () => {
  const requestedUrls = []
  const client = new OpenAI({
    apiKey: 'sk-test',
    baseURL: normalizeProviderBaseUrl('openai', 'https://gateway.example.com/'),
    maxRetries: 0,
    fetch: async input => {
      requestedUrls.push(String(input))
      return new Response(JSON.stringify({ object: 'list', data: [] }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    },
  })

  await client.models.list()
  assert.deepEqual(requestedUrls, ['https://gateway.example.com/models'])
})

test('falls back from a root HTML response to runtime /v1 and caches only the resolved runtime URL', async () => {
  const configuredBaseUrl = 'https://gateway.example.com/'
  const requestedUrls = []
  const listModels = () => withProviderBaseUrlFallback('openai', configuredBaseUrl, async baseURL => {
    const client = new OpenAI({
      apiKey: 'sk-test',
      baseURL,
      maxRetries: 0,
      fetch: async input => {
        const url = String(input)
        requestedUrls.push(url)
        if (url === 'https://gateway.example.com/models') {
          return new Response('<!doctype html><html>gateway home</html>', {
            status: 200,
            headers: { 'content-type': 'text/html' },
          })
        }
        return new Response(JSON.stringify({
          object: 'list',
          data: [{ id: 'model-a' }, { id: 'model-b' }, { id: 'model-c' }],
        }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      },
    })
    const page = await client.models.list()
    if (!page?.body || typeof page.body !== 'object' || !Array.isArray(page.body.data)) {
      throw invalidProviderResponse('模型接口不是 API JSON')
    }
    return page.data.map(model => model.id)
  })

  assert.deepEqual(await listModels(), ['model-a', 'model-b', 'model-c'])
  assert.deepEqual(requestedUrls, [
    'https://gateway.example.com/models',
    'https://gateway.example.com/v1/models',
  ])
  assert.equal(configuredBaseUrl, 'https://gateway.example.com/')

  requestedUrls.length = 0
  assert.deepEqual(await listModels(), ['model-a', 'model-b', 'model-c'])
  assert.deepEqual(requestedUrls, ['https://gateway.example.com/v1/models'])
})

test('does not add a second /v1 for Anthropic or retry authentication failures', async () => {
  const anthropicCandidates = []
  await assert.rejects(
    withProviderBaseUrlFallback('anthropic', 'https://claude.example/v1', async baseURL => {
      anthropicCandidates.push(baseURL)
      throw invalidProviderResponse('not anthropic json')
    }),
    /not anthropic json/
  )
  assert.deepEqual(anthropicCandidates, ['https://claude.example/v1'])

  const openaiCandidates = []
  const unauthorized = Object.assign(new Error('Unauthorized'), { status: 401 })
  await assert.rejects(
    withProviderBaseUrlFallback('openai', 'https://gateway.example/', async baseURL => {
      openaiCandidates.push(baseURL)
      throw unauthorized
    }),
    error => error === unauthorized
  )
  assert.deepEqual(openaiCandidates, ['https://gateway.example/'])
})

test('admin model discovery rejects root HTML, retries /v1, and reuses the cached candidate', async () => {
  const requestedUrls = []
  globalThis.fetch = async input => {
    const url = String(input)
    requestedUrls.push(url)
    if (url === 'https://gateway.example.com/models') {
      return new Response('<!doctype html><html>gateway home</html>', {
        status: 200,
        headers: { 'content-type': 'text/html' },
      })
    }
    return new Response(JSON.stringify({
      object: 'list',
      data: [
        { id: 'relay-chat-a' },
        { id: 'relay-chat-b' },
        { id: 'relay-vision-c' },
      ],
    }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    })
  }

  const firstModels = await listProviderModels('openai', 'sk-test', 'https://gateway.example.com/')
  assert.deepEqual(firstModels.map(model => model.id), ['relay-chat-a', 'relay-chat-b', 'relay-vision-c'])
  assert.deepEqual(requestedUrls, [
    'https://gateway.example.com/models',
    'https://gateway.example.com/v1/models',
  ])

  requestedUrls.length = 0
  await listProviderModels('openai', 'sk-test', 'https://gateway.example.com/')
  assert.deepEqual(requestedUrls, ['https://gateway.example.com/v1/models'])
})

test('chat stream retries /v1 when the root returns HTML without protocol chunks and completes once', async () => {
  const requestedUrls = []
  globalThis.fetch = async input => {
    const url = String(input)
    requestedUrls.push(url)
    if (url === 'https://gateway.example.com/chat/completions') {
      return new Response('<!doctype html><html>gateway home</html>', {
        status: 200,
        headers: { 'content-type': 'text/html' },
      })
    }
    const events = [
      'data: {"id":"one","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"成功"},"finish_reason":null}]}',
      'data: {"id":"one","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":1}}',
      'data: [DONE]',
      '',
    ].join('\n\n')
    return new Response(events, {
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
    })
  }

  const deltas = []
  const completions = []
  await streamWithCredential({
    provider: 'openai',
    modelId: 'relay-chat-a',
    messages: [{ role: 'user', content: '你好' }],
    temperature: 0.5,
    maxTokens: 32,
    credentials: { apiKey: 'sk-test', baseUrl: 'https://gateway.example.com/' },
    onDelta: delta => deltas.push(delta),
    onDone: (text, usage) => completions.push({ text, usage }),
  })

  assert.deepEqual(requestedUrls, [
    'https://gateway.example.com/chat/completions',
    'https://gateway.example.com/v1/chat/completions',
  ])
  assert.deepEqual(deltas, ['成功'])
  assert.deepEqual(completions, [{ text: '成功', usage: { inputTokens: 2, outputTokens: 1 } }])
})

test('classifies offline, timeout and upstream authentication errors safely', () => {
  const dnsError = new Error('Connection error')
  dnsError.cause = Object.assign(new Error('getaddrinfo ENOTFOUND api.example.com'), { code: 'ENOTFOUND' })
  assert.deepEqual(classifyProviderError(dnsError), {
    statusCode: 503,
    code: 'API_DNS_ERROR',
    error: '无法解析外部 API 域名，请检查服务器 DNS、网络或改用可访问的 Base URL',
    retryable: true,
    canSave: true,
  })

  const timeout = Object.assign(new Error('Request timed out'), { code: 'ETIMEDOUT' })
  assert.equal(classifyProviderError(timeout).code, 'API_TIMEOUT')
  assert.equal(classifyProviderError(timeout).canSave, true)

  const unauthorized = Object.assign(new Error('Incorrect API key provided'), { status: 401 })
  assert.equal(classifyProviderError(unauthorized).statusCode, 422)
  assert.equal(classifyProviderError(unauthorized).code, 'API_KEY_INVALID')
  assert.equal(classifyProviderError(unauthorized).canSave, false)
})

test('uses a bounded configurable validation timeout', () => {
  process.env.API_VALIDATION_TIMEOUT_MS = '4321'
  assert.equal(providerValidationTimeoutMs(), 4321)
  process.env.API_VALIDATION_TIMEOUT_MS = 'not-a-number'
  assert.equal(providerValidationTimeoutMs(), 15_000)
})
