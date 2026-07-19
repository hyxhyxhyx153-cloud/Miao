import { createWriteStream, mkdirSync } from 'node:fs'
import { join, extname } from 'node:path'
import { randomUUID } from 'node:crypto'
import { pipeline } from 'node:stream/promises'
import OSS from 'ali-oss'

// Ensure uploads directory exists
const UPLOADS_DIR = join(process.cwd(), 'uploads')
try { mkdirSync(UPLOADS_DIR, { recursive: true }) } catch { /* already exists */ }

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

// ── Fastify plugin ────────────────────────────────────────────────────────────

export default async function mediaRoutes(fastify) {
  const auth = { onRequest: [fastify.authenticate] }

  // Register @fastify/static to serve uploads folder
  await fastify.register(
    (await import('@fastify/static')).default,
    {
      root: UPLOADS_DIR,
      prefix: '/uploads/',
      decorateReply: false,
    }
  )

  // POST /media/upload-url — returns an OSS pre-signed URL or the local upload endpoint
  fastify.post('/media/upload-url', auth, async (req) => {
    const { filename = 'file', contentType = 'application/octet-stream' } = req.body || {}
    const client = ossClient()

    if (client) {
      const fileKey = `uploads/${randomUUID()}/${filename}`
      const uploadUrl = client.signatureUrl(fileKey, {
        method: 'PUT',
        expires: 3600,
        'Content-Type': contentType,
      })
      const endpoint = String(process.env.OSS_PUBLIC_URL || process.env.OSS_ENDPOINT || '').replace(/\/$/, '')
      const fileUrl = endpoint
        ? `${endpoint}/${fileKey}`
        : `https://${process.env.OSS_BUCKET}.${process.env.OSS_REGION}.aliyuncs.com/${fileKey}`
      return { upload_url: uploadUrl, file_url: fileUrl, method: 'PUT', content_type: contentType }
    }

    // Local dev: return the local upload endpoint
    const localBase = process.env.LOCAL_BASE_URL || 'http://10.0.2.2:3000'
    return {
      upload_url: `${localBase}/api/v1/media/upload`,
      file_url: null,
      method: 'POST',
      content_type: 'multipart/form-data',
      note: 'Use POST /api/v1/media/upload with multipart/form-data field "file"',
    }
  })

  // POST /media/upload — local dev multipart upload
  fastify.post('/media/upload', auth, async (req, reply) => {
    const data = await req.file()
    if (!data) return reply.code(400).send({ error: 'No file provided' })

    const ext = extname(data.filename) || ''
    const safeExt = ext.replace(/[^a-zA-Z0-9.]/g, '').slice(0, 10)
    const newFilename = `${randomUUID()}${safeExt}`
    const destPath = join(UPLOADS_DIR, newFilename)

    if (!/^image\/(jpeg|png|webp|gif)$/.test(data.mimetype)) {
      data.file.resume()
      return reply.code(400).send({ error: '仅支持 JPEG、PNG、WebP 或 GIF 图片' })
    }

    const client = ossClient()
    if (client) {
      const fileKey = `uploads/${req.user.sub}/${newFilename}`
      await client.putStream(fileKey, data.file, { headers: { 'Content-Type': data.mimetype } })
      const endpoint = String(process.env.OSS_PUBLIC_URL || process.env.OSS_ENDPOINT || '').replace(/\/$/, '')
      const fileUrl = endpoint
        ? `${endpoint}/${fileKey}`
        : `https://${process.env.OSS_BUCKET}.${process.env.OSS_REGION}.aliyuncs.com/${fileKey}`
      return { file_url: fileUrl, filename: newFilename }
    }

    await pipeline(data.file, createWriteStream(destPath))

    const localBase = process.env.LOCAL_BASE_URL || 'http://10.0.2.2:3000'
    const fileUrl = `${localBase}/api/v1/uploads/${newFilename}`
    return { file_url: fileUrl, filename: newFilename }
  })
}
