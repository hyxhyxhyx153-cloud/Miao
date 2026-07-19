import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { extname, relative, resolve, sep } from 'node:path'
import OSS from 'ali-oss'
import { pool } from '../db/client.js'
import { decryptSecret } from './secretVault.js'
import {
  imageRequestTimeoutMs,
  invalidProviderResponse,
  providerEndpointUrl,
  providerFetch,
  withProviderBaseUrlFallback,
} from './providerApi.js'

const DEFAULT_BASE_URL = 'https://api.openai.com/v1'
const UPLOADS_DIR = resolve(process.cwd(), 'uploads')
const GENERATED_DIR = resolve(UPLOADS_DIR, 'generated')
const SETTING_KEYS = [
  'image_generation_enabled',
  'image_generation_model',
  'image_generation_quality',
  'image_generation_size',
]

const MIME_BY_EXTENSION = {
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.webp': 'image/webp',
}

function detectImageMime(bytes) {
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return 'image/png'
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return 'image/jpeg'
  }
  if (bytes.length >= 12 && bytes.subarray(0, 4).toString('ascii') === 'RIFF' && bytes.subarray(8, 12).toString('ascii') === 'WEBP') {
    return 'image/webp'
  }
  return null
}

function settingValue(rows, key, fallback) {
  const value = rows.find(row => row.key === key)?.value
  return value == null ? fallback : value
}

function booleanSetting(value, fallback) {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') return value.toLowerCase() === 'true'
  return fallback
}

function sizeForAspectRatio(configuredSize, aspectRatio) {
  if (configuredSize && configuredSize !== 'auto') return configuredSize
  return {
    portrait: '1024x1536',
    landscape: '1536x1024',
    square: '1024x1024',
  }[aspectRatio] || '1024x1024'
}

export function isRetryableImageFailure(error) {
  const status = Number(error?.status)
  const message = String(error?.message || '')
  return [408, 500, 502, 503, 504, 520, 522, 524].includes(status)
    || /(?:timeout|timed out|gateway timeout|\b524\b)/i.test(message)
}

export function imageQualityAttempts(configuredQuality) {
  const quality = ['low', 'medium', 'high'].includes(configuredQuality)
    ? configuredQuality
    : 'medium'
  return quality === 'low' ? ['low'] : [quality, 'low']
}

function publicImageUrl(filename) {
  const baseUrl = String(process.env.LOCAL_BASE_URL || 'http://10.0.2.2:3000').replace(/\/+$/, '')
  return `${baseUrl}/api/v1/uploads/generated/${filename}`
}

function ossClient() {
  if (!process.env.OSS_BUCKET || !process.env.OSS_ACCESS_KEY || !process.env.OSS_SECRET_KEY) return null
  return new OSS({
    region: process.env.OSS_REGION,
    endpoint: process.env.OSS_ENDPOINT || undefined,
    bucket: process.env.OSS_BUCKET,
    accessKeyId: process.env.OSS_ACCESS_KEY,
    accessKeySecret: process.env.OSS_SECRET_KEY,
    secure: true,
  })
}

async function persistGeneratedImage(bytes, mimeType = 'image/png') {
  const extension = { 'image/jpeg': 'jpg', 'image/webp': 'webp' }[mimeType] || 'png'
  const filename = `${randomUUID()}.${extension}`
  const client = ossClient()
  if (client) {
    const fileKey = `uploads/generated/${filename}`
    await client.put(fileKey, bytes, { headers: { 'Content-Type': mimeType } })
    const endpoint = String(process.env.OSS_PUBLIC_URL || process.env.OSS_ENDPOINT || '').replace(/\/+$/, '')
    return endpoint
      ? `${endpoint}/${fileKey}`
      : `https://${process.env.OSS_BUCKET}.${process.env.OSS_REGION}.aliyuncs.com/${fileKey}`
  }
  await mkdir(GENERATED_DIR, { recursive: true })
  await writeFile(resolve(GENERATED_DIR, filename), bytes)
  return publicImageUrl(filename)
}

function isInsideUploads(filePath) {
  const pathFromUploads = relative(UPLOADS_DIR, filePath)
  return pathFromUploads && !pathFromUploads.startsWith('..') && !pathFromUploads.includes(`..${sep}`)
}

function localUploadPath(urlValue) {
  try {
    const url = new URL(urlValue, 'http://local.invalid')
    const marker = '/api/v1/uploads/'
    const index = url.pathname.indexOf(marker)
    if (index < 0) return null
    const relativePath = decodeURIComponent(url.pathname.slice(index + marker.length))
    const filePath = resolve(UPLOADS_DIR, relativePath)
    return isInsideUploads(filePath) ? filePath : null
  } catch {
    return null
  }
}

function allowedRemoteReferenceHosts() {
  const hosts = new Set()
  for (const value of [process.env.OSS_PUBLIC_URL, process.env.OSS_ENDPOINT]) {
    if (!value) continue
    try { hosts.add(new URL(value.includes('://') ? value : `https://${value}`).host) } catch {}
  }
  if (process.env.OSS_BUCKET && process.env.OSS_REGION) {
    hosts.add(`${process.env.OSS_BUCKET}.${process.env.OSS_REGION}.aliyuncs.com`)
  }
  return hosts
}

async function loadReferenceImage(urlValue) {
  const localPath = localUploadPath(urlValue)
  if (localPath) {
    const declaredMimeType = MIME_BY_EXTENSION[extname(localPath).toLowerCase()]
    if (!declaredMimeType) throw new Error('人格参考图仅支持 JPEG、PNG 或 WebP')
    const bytes = await readFile(localPath)
    if (bytes.length > 20 * 1024 * 1024) throw new Error('人格参考图不能超过 20MB')
    const mimeType = detectImageMime(bytes)
    if (!mimeType) throw new Error('人格参考图文件内容无效')
    return { bytes, mimeType, filename: `reference${extname(localPath).toLowerCase()}` }
  }

  const url = new URL(urlValue)
  if (!['http:', 'https:'].includes(url.protocol) || !allowedRemoteReferenceHosts().has(url.host)) {
    throw new Error('人格参考图必须来自本站上传服务')
  }
  const response = await providerFetch(url, { signal: AbortSignal.timeout(20_000) })
  if (!response.ok) throw new Error(`读取人格参考图失败 (${response.status})`)
  const declaredMimeType = String(response.headers.get('content-type') || '').split(';')[0]
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(declaredMimeType)) {
    throw new Error('人格参考图仅支持 JPEG、PNG 或 WebP')
  }
  const bytes = Buffer.from(await response.arrayBuffer())
  if (bytes.length > 20 * 1024 * 1024) throw new Error('人格参考图不能超过 20MB')
  const mimeType = detectImageMime(bytes)
  if (!mimeType) throw new Error('人格参考图文件内容无效')
  return { bytes, mimeType, filename: `reference.${mimeType.split('/')[1]}` }
}

async function imageCredentials() {
  const { rows } = await pool.query(
    `SELECT id,api_key,base_url FROM api_keys
     WHERE provider='gpt-image' AND is_active=true ORDER BY created_at DESC`
  )
  if (rows.length) {
    return rows.map(row => ({
      id: row.id,
      apiKey: decryptSecret(row.api_key),
      baseUrl: row.base_url || DEFAULT_BASE_URL,
    }))
  }
  const envKey = process.env.GPT_IMAGE_API_KEY
  return envKey ? [{ id: null, apiKey: envKey, baseUrl: process.env.GPT_IMAGE_BASE_URL || DEFAULT_BASE_URL }] : []
}

async function parseImageResponse(response) {
  let payload
  try {
    payload = await response.json()
  } catch {
    if (response.ok) throw invalidProviderResponse('GPT Image API 返回了非 JSON 内容')
    payload = {}
  }
  if (!response.ok) {
    const message = payload?.error?.message || payload?.message || `GPT Image 请求失败 (${response.status})`
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  const encoded = payload?.data?.[0]?.b64_json
  if (encoded) {
    const bytes = Buffer.from(encoded, 'base64')
    const mimeType = detectImageMime(bytes)
    if (!mimeType) throw new Error('GPT Image API 返回的图片内容无效')
    return { bytes, mimeType, usage: payload.usage || null }
  }
  const temporaryUrl = payload?.data?.[0]?.url
  if (temporaryUrl) {
    const url = new URL(temporaryUrl)
    if (url.protocol !== 'https:') throw new Error('GPT Image API 返回了不安全的图片地址')
    const imageResponse = await providerFetch(url, { signal: AbortSignal.timeout(30_000) })
    if (!imageResponse.ok) throw new Error('下载 GPT Image 结果失败')
    const bytes = Buffer.from(await imageResponse.arrayBuffer())
    if (bytes.length > 50 * 1024 * 1024) throw new Error('GPT Image 返回的图片过大')
    const mimeType = detectImageMime(bytes)
    if (!mimeType) throw new Error('GPT Image API 返回的图片内容无效')
    return { bytes, mimeType, usage: payload.usage || null }
  }
  throw invalidProviderResponse('GPT Image API 返回的 JSON 中没有图片数据')
}

async function requestImage({ credentials, prompt, references, model, quality, size, signal }) {
  const requestSignal = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(imageRequestTimeoutMs())])
    : AbortSignal.timeout(imageRequestTimeoutMs())
  return withProviderBaseUrlFallback('gpt-image', credentials.baseUrl, async baseUrl => {
    if (references.length) {
      const form = new FormData()
      form.append('model', model)
      form.append('prompt', prompt)
      form.append('quality', quality)
      form.append('size', size)
      form.append('output_format', 'png')
      for (const reference of references) {
        form.append('image[]', new Blob([reference.bytes], { type: reference.mimeType }), reference.filename)
      }
      const response = await providerFetch(providerEndpointUrl('gpt-image', baseUrl, '/images/edits'), {
        method: 'POST',
        headers: { Authorization: `Bearer ${credentials.apiKey}` },
        body: form,
        signal: requestSignal,
      })
      return parseImageResponse(response)
    }

    const response = await providerFetch(providerEndpointUrl('gpt-image', baseUrl, '/images/generations'), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${credentials.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ model, prompt, quality, size, output_format: 'png', n: 1 }),
      signal: requestSignal,
    })
    return parseImageResponse(response)
  })
}

export async function getImageGenerationConfig() {
  const [{ rows }, { rows: credentialRows }] = await Promise.all([
    pool.query(
      `SELECT key,value FROM system_settings WHERE key=ANY($1::text[])`,
      [SETTING_KEYS]
    ),
    pool.query(
      `SELECT EXISTS(
         SELECT 1 FROM api_keys WHERE provider='gpt-image' AND is_active=true
       ) AS configured`
    ),
  ])
  const quality = String(settingValue(rows, 'image_generation_quality', 'medium'))
  const size = String(settingValue(rows, 'image_generation_size', 'auto'))
  const enabled = booleanSetting(settingValue(rows, 'image_generation_enabled', true), true)
  const configured = Boolean(credentialRows[0]?.configured || process.env.GPT_IMAGE_API_KEY)
  return {
    enabled,
    configured,
    ready: enabled && configured,
    model: String(settingValue(rows, 'image_generation_model', 'gpt-image-2')),
    quality: ['low', 'medium', 'high'].includes(quality) ? quality : 'medium',
    size: ['auto', '1024x1024', '1024x1536', '1536x1024'].includes(size) ? size : 'auto',
  }
}

export function validateReferenceImageUrls(value) {
  if (value == null) return []
  if (!Array.isArray(value)) throw new Error('referenceImageUrls must be an array')
  if (value.length > 3) throw new Error('每个人格最多上传 3 张参考图')
  return value.map(item => {
    if (typeof item !== 'string' || item.length > 1024) throw new Error('参考图地址无效')
    const url = new URL(item)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('参考图地址无效')
    if (!localUploadPath(url.toString()) && !allowedRemoteReferenceHosts().has(url.host)) {
      throw new Error('参考图必须由本站上传服务提供')
    }
    return url.toString()
  })
}

export async function generatePersonaImage({ prompt, referenceImageUrls = [], aspectRatio, signal }) {
  const config = await getImageGenerationConfig()
  if (!config.enabled) throw new Error('图片生成功能当前未启用')
  const credentialsList = await imageCredentials()
  if (!credentialsList.length) throw new Error('GPT Image 2 API 尚未配置')

  const trimmedPrompt = String(prompt || '').trim()
  if (!trimmedPrompt) throw new Error('图片提示词不能为空')
  if (trimmedPrompt.length > 32_000) throw new Error('图片提示词过长')
  const urls = validateReferenceImageUrls(referenceImageUrls)
  const references = await Promise.all(urls.map(loadReferenceImage))
  const size = sizeForAspectRatio(config.size, aspectRatio)

  let lastError
  for (const credentials of credentialsList) {
    for (const quality of imageQualityAttempts(config.quality)) {
      try {
        const result = await requestImage({
          credentials,
          prompt: trimmedPrompt,
          references,
          model: config.model,
          quality,
          size,
          signal,
        })
        return {
          url: await persistGeneratedImage(result.bytes, result.mimeType),
          model: config.model,
          quality,
          size,
          usage: result.usage,
          apiKeyId: credentials.id,
        }
      } catch (error) {
        lastError = error
        if (signal?.aborted) throw error
        const canDowngrade = quality !== 'low' && isRetryableImageFailure(error)
        if (!canDowngrade) break
      }
    }
  }
  throw lastError || new Error('GPT Image 2 图片生成失败')
}
