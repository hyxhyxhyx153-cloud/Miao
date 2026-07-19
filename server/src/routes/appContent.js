import { createWriteStream, mkdirSync } from 'node:fs'
import { open, readdir, rename, stat, unlink } from 'node:fs/promises'
import { basename, join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { pipeline } from 'node:stream/promises'
import { pool } from '../db/client.js'
import { recordAudit } from '../services/audit.js'
import {
  APP_RELEASE_SETTING_KEYS,
  LEGAL_DOCUMENT_MAX_LENGTH,
  LEGAL_SETTING_KEYS,
  announcementToDto,
  appReleaseFromSettings,
  appReleaseToSettingEntries,
  isApkZipMagic,
  legalFromSettings,
  legalToSettingEntries,
  settingsRowsToObject,
  validateAnnouncement,
  validateAppRelease,
  validateLegal,
} from '../services/appContent.js'

const MAX_APK_BYTES = 200 * 1024 * 1024
const MAX_ZIP_TAIL_BYTES = 65_535 + 22
const MAX_CENTRAL_DIRECTORY_BYTES = 16 * 1024 * 1024
const MAX_RETAINED_APKS = 10
// A UTF-16 code unit can expand to six bytes as a JSON escape. Size this for
// both documents, while retaining a four MiB floor and keeping the allowance
// scoped to this authenticated route.
const LEGAL_REQUEST_BODY_LIMIT_BYTES = Math.max(
  4 * 1024 * 1024,
  LEGAL_DOCUMENT_MAX_LENGTH * 2 * 6 + 16 * 1024
)
const RELEASES_DIR = join(process.cwd(), 'uploads', 'releases')
mkdirSync(RELEASES_DIR, { recursive: true })

async function loadSettings(keys) {
  const { rows } = await pool.query(
    `SELECT key,value,updated_at FROM system_settings WHERE key=ANY($1::text[])`,
    [keys]
  )
  const updatedAt = rows.reduce((latest, row) => {
    if (!row.updated_at) return latest
    return !latest || row.updated_at > latest ? row.updated_at : latest
  }, null)
  return { values: settingsRowsToObject(rows), updatedAt }
}

async function upsertSettings(entries) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    for (const [key, value] of entries) {
      await client.query(
        `INSERT INTO system_settings(key,value) VALUES($1,$2::jsonb)
         ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value,updated_at=now()`,
        [key, JSON.stringify(value)]
      )
    }
    await client.query('COMMIT')
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  } finally {
    client.release()
  }
}

function badRequest(reply, error) {
  return reply.code(400).send({ error: error.message })
}

function requestBaseUrl() {
  const configured = String(process.env.LOCAL_BASE_URL || '').trim().replace(/\/$/, '')
  if (!configured) return ''
  try {
    const url = new URL(configured)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return ''
    return url.origin
  } catch {
    return ''
  }
}

function isUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
}

export async function isValidApkArchive(path, size) {
  const handle = await open(path, 'r')
  try {
    const magic = Buffer.alloc(4)
    await handle.read(magic, 0, magic.length, 0)
    if (!isApkZipMagic(magic)) return false

    const tailLength = Math.min(size, MAX_ZIP_TAIL_BYTES)
    const tail = Buffer.alloc(tailLength)
    await handle.read(tail, 0, tail.length, size - tailLength)

    let eocdOffset = -1
    for (let offset = tail.length - 22; offset >= 0; offset--) {
      if (tail.readUInt32LE(offset) !== 0x06054b50) continue
      const commentLength = tail.readUInt16LE(offset + 20)
      if (offset + 22 + commentLength === tail.length) {
        eocdOffset = offset
        break
      }
    }
    if (eocdOffset < 0) return false

    const diskNumber = tail.readUInt16LE(eocdOffset + 4)
    const centralDirectoryDisk = tail.readUInt16LE(eocdOffset + 6)
    const entriesOnDisk = tail.readUInt16LE(eocdOffset + 8)
    const totalEntries = tail.readUInt16LE(eocdOffset + 10)
    const centralDirectorySize = tail.readUInt32LE(eocdOffset + 12)
    const centralDirectoryOffset = tail.readUInt32LE(eocdOffset + 16)
    if (
      diskNumber !== 0
      || centralDirectoryDisk !== 0
      || entriesOnDisk !== totalEntries
      || totalEntries < 1
      || totalEntries > 100_000
      || centralDirectorySize < 46
      || centralDirectorySize > MAX_CENTRAL_DIRECTORY_BYTES
      || centralDirectoryOffset + centralDirectorySize > size
    ) return false

    const directory = Buffer.alloc(centralDirectorySize)
    await handle.read(directory, 0, directory.length, centralDirectoryOffset)
    let offset = 0
    let foundManifest = false
    for (let entry = 0; entry < totalEntries; entry++) {
      if (offset + 46 > directory.length || directory.readUInt32LE(offset) !== 0x02014b50) return false
      const flags = directory.readUInt16LE(offset + 8)
      const fileNameLength = directory.readUInt16LE(offset + 28)
      const extraLength = directory.readUInt16LE(offset + 30)
      const commentLength = directory.readUInt16LE(offset + 32)
      const nextOffset = offset + 46 + fileNameLength + extraLength + commentLength
      if (nextOffset > directory.length) return false
      const fileName = directory.subarray(offset + 46, offset + 46 + fileNameLength).toString('utf8')
      if (fileName === 'AndroidManifest.xml' && (flags & 0x1) === 0) foundManifest = true
      offset = nextOffset
    }
    return foundManifest && offset === directory.length
  } finally {
    await handle.close()
  }
}

async function removeQuietly(path) {
  try { await unlink(path) } catch { /* nothing to clean up */ }
}

async function cleanupReleaseFiles(publishedDownloadUrl = '') {
  const entries = await readdir(RELEASES_DIR, { withFileTypes: true })
  const files = await Promise.all(entries
    .filter(entry => entry.isFile() && entry.name.toLowerCase().endsWith('.apk'))
    .map(async entry => {
      const path = join(RELEASES_DIR, entry.name)
      return { name: entry.name, path, modifiedAt: (await stat(path)).mtimeMs }
    }))
  files.sort((left, right) => right.modifiedAt - left.modifiedAt)
  const retained = new Set(files.slice(0, MAX_RETAINED_APKS).map(file => file.name))
  try {
    const publishedPath = new URL(publishedDownloadUrl, 'http://local.invalid').pathname
    if (publishedPath.startsWith('/api/v1/uploads/releases/')) retained.add(basename(publishedPath))
  } catch { /* invalid legacy value cannot identify a published local file */ }
  await Promise.all(files
    .filter(file => !retained.has(file.name))
    .map(file => removeQuietly(file.path)))
}

export default async function appContentRoutes(fastify) {
  const adminAuth = { onRequest: [fastify.authenticate, fastify.requireAdmin] }

  fastify.get('/app/version', async (req, reply) => {
    let currentVersionCode = null
    if (req.query?.versionCode !== undefined) {
      currentVersionCode = Number(req.query.versionCode)
      if (!Number.isInteger(currentVersionCode) || currentVersionCode < 1 || currentVersionCode > 2147483647) {
        return reply.code(400).send({ error: 'versionCode must be a positive integer' })
      }
    }
    const { values, updatedAt } = await loadSettings(APP_RELEASE_SETTING_KEYS)
    return appReleaseFromSettings(values, currentVersionCode, updatedAt)
  })

  fastify.get('/app/announcements', async () => {
    const { rows } = await pool.query(
      `SELECT id,title,content,type,is_active,is_pinned,starts_at,ends_at,created_at,updated_at
       FROM announcements
       WHERE is_active=true
         AND (starts_at IS NULL OR starts_at<=now())
         AND (ends_at IS NULL OR ends_at>now())
       ORDER BY is_pinned DESC,created_at DESC
       LIMIT 50`
    )
    return rows.map(announcementToDto)
  })

  fastify.get('/app/legal', async () => {
    const { values, updatedAt } = await loadSettings(LEGAL_SETTING_KEYS)
    return legalFromSettings(values, updatedAt)
  })

  fastify.get('/admin/app-release', adminAuth, async () => {
    const { values, updatedAt } = await loadSettings(APP_RELEASE_SETTING_KEYS)
    return appReleaseFromSettings(values, null, updatedAt)
  })

  fastify.put('/admin/app-release', adminAuth, async (req, reply) => {
    const { values } = await loadSettings(APP_RELEASE_SETTING_KEYS)
    const current = appReleaseFromSettings(values)
    let update
    try {
      update = validateAppRelease(req.body, current)
    } catch (error) {
      return badRequest(reply, error)
    }
    await upsertSettings(appReleaseToSettingEntries(update))
    await recordAudit(req, 'app_release.update', 'system_settings', 'android_release', {
      fields: Object.keys(update),
    })
    const result = await loadSettings(APP_RELEASE_SETTING_KEYS)
    return appReleaseFromSettings(result.values, null, result.updatedAt)
  })

  fastify.post('/admin/app-release/upload', adminAuth, async (req, reply) => {
    let data
    try {
      data = await req.file({ limits: { fileSize: MAX_APK_BYTES, files: 1 } })
    } catch (error) {
      const isTooLarge = error?.code === 'FST_REQ_FILE_TOO_LARGE'
        || error?.name === 'RequestFileTooLargeError'
      return reply.code(isTooLarge ? 413 : 400).send({ error: isTooLarge ? 'APK exceeds 200 MB' : 'Invalid upload' })
    }
    if (!data) return reply.code(400).send({ error: 'No APK file provided' })
    if (!/\.apk$/i.test(data.filename || '')) {
      data.file.resume()
      return reply.code(400).send({ error: 'Only .apk files are accepted' })
    }

    const fileName = `miao-${Date.now()}-${randomUUID()}.apk`
    const temporaryPath = join(RELEASES_DIR, `${fileName}.part`)
    const destinationPath = join(RELEASES_DIR, fileName)
    try {
      await pipeline(data.file, createWriteStream(temporaryPath, { flags: 'wx' }))
      if (data.file.truncated) {
        await removeQuietly(temporaryPath)
        return reply.code(413).send({ error: 'APK exceeds 200 MB' })
      }
      const fileStats = await stat(temporaryPath)
      if (fileStats.size < 4 || fileStats.size > MAX_APK_BYTES) {
        await removeQuietly(temporaryPath)
        return reply.code(fileStats.size > MAX_APK_BYTES ? 413 : 400).send({ error: 'Invalid APK file' })
      }
      if (!await isValidApkArchive(temporaryPath, fileStats.size)) {
        await removeQuietly(temporaryPath)
        return reply.code(400).send({ error: 'APK archive is invalid or missing AndroidManifest.xml' })
      }
      await rename(temporaryPath, destinationPath)
      const path = `/api/v1/uploads/releases/${fileName}`
      // Without an explicit public base URL, keep the path relative. The
      // Android client resolves it against its active API endpoint, which is
      // reachable from an emulator or physical device. Deriving this value
      // from Host would commonly persist localhost when Vite proxies uploads.
      const baseUrl = requestBaseUrl()
      const downloadUrl = baseUrl ? `${baseUrl}${path}` : path
      await recordAudit(req, 'app_release.upload', 'release_apk', fileName, { size: fileStats.size })
      try {
        const { values } = await loadSettings(['android_download_url'])
        await cleanupReleaseFiles(values.android_download_url)
      } catch (cleanupError) {
        fastify.log.warn(cleanupError, 'Failed to clean up old APK releases')
      }
      return { downloadUrl }
    } catch (error) {
      await removeQuietly(temporaryPath)
      fastify.log.error(error, 'Failed to store APK upload')
      return reply.code(500).send({ error: 'Failed to store APK' })
    }
  })

  fastify.get('/admin/announcements', adminAuth, async () => {
    const { rows } = await pool.query(
      `SELECT id,title,content,type,is_active,is_pinned,starts_at,ends_at,created_at,updated_at
       FROM announcements ORDER BY is_pinned DESC,created_at DESC LIMIT 500`
    )
    return rows.map(announcementToDto)
  })

  fastify.post('/admin/announcements', adminAuth, async (req, reply) => {
    let value
    try {
      value = validateAnnouncement(req.body)
    } catch (error) {
      return badRequest(reply, error)
    }
    const { rows } = await pool.query(
      `INSERT INTO announcements(title,content,type,is_active,is_pinned,starts_at,ends_at)
       VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING *`,
      [value.title, value.content, value.type, value.isActive, value.isPinned, value.startsAt, value.endsAt]
    )
    const announcement = announcementToDto(rows[0])
    await recordAudit(req, 'announcement.create', 'announcement', announcement.id, {
      type: announcement.type,
      isActive: announcement.isActive,
    })
    return announcement
  })

  fastify.patch('/admin/announcements/:id', adminAuth, async (req, reply) => {
    if (!isUuid(req.params.id)) return reply.code(400).send({ error: 'Invalid announcement id' })
    const { rows: existingRows } = await pool.query(`SELECT * FROM announcements WHERE id=$1`, [req.params.id])
    if (!existingRows[0]) return reply.code(404).send({ error: 'Announcement not found' })
    let value
    try {
      value = validateAnnouncement(req.body, { partial: true, current: announcementToDto(existingRows[0]) })
    } catch (error) {
      return badRequest(reply, error)
    }
    const has = key => Object.hasOwn(value, key)
    const { rows } = await pool.query(
      `UPDATE announcements SET
         title=CASE WHEN $1::boolean THEN $2 ELSE title END,
         content=CASE WHEN $3::boolean THEN $4 ELSE content END,
         type=CASE WHEN $5::boolean THEN $6 ELSE type END,
         is_active=CASE WHEN $7::boolean THEN $8 ELSE is_active END,
         is_pinned=CASE WHEN $9::boolean THEN $10 ELSE is_pinned END,
         starts_at=CASE WHEN $11::boolean THEN $12::timestamptz ELSE starts_at END,
         ends_at=CASE WHEN $13::boolean THEN $14::timestamptz ELSE ends_at END,
         updated_at=now()
       WHERE id=$15 RETURNING *`,
      [
        has('title'), value.title ?? null,
        has('content'), value.content ?? null,
        has('type'), value.type ?? null,
        has('isActive'), value.isActive ?? null,
        has('isPinned'), value.isPinned ?? null,
        has('startsAt'), value.startsAt ?? null,
        has('endsAt'), value.endsAt ?? null,
        req.params.id,
      ]
    )
    const announcement = announcementToDto(rows[0])
    await recordAudit(req, 'announcement.update', 'announcement', announcement.id, { fields: Object.keys(value) })
    return announcement
  })

  fastify.delete('/admin/announcements/:id', adminAuth, async (req, reply) => {
    if (!isUuid(req.params.id)) return reply.code(400).send({ error: 'Invalid announcement id' })
    const result = await pool.query(`DELETE FROM announcements WHERE id=$1`, [req.params.id])
    if (!result.rowCount) return reply.code(404).send({ error: 'Announcement not found' })
    await recordAudit(req, 'announcement.delete', 'announcement', req.params.id)
    return { success: true }
  })

  fastify.get('/admin/legal', adminAuth, async () => {
    const { values, updatedAt } = await loadSettings(LEGAL_SETTING_KEYS)
    return legalFromSettings(values, updatedAt)
  })

  fastify.put('/admin/legal', {
    ...adminAuth,
    bodyLimit: LEGAL_REQUEST_BODY_LIMIT_BYTES,
  }, async (req, reply) => {
    let update
    try {
      update = validateLegal(req.body)
    } catch (error) {
      return badRequest(reply, error)
    }
    await upsertSettings(legalToSettingEntries(update))
    await recordAudit(req, 'legal.update', 'system_settings', 'legal', { fields: Object.keys(update) })
    const result = await loadSettings(LEGAL_SETTING_KEYS)
    return legalFromSettings(result.values, result.updatedAt)
  })
}
