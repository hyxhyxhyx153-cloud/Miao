import crypto from 'node:crypto'

const PREFIX = 'enc:v1:'

function encryptionKey() {
  const source = process.env.DATA_ENCRYPTION_KEY
    || process.env.JWT_REFRESH_SECRET
    || process.env.JWT_SECRET
    || 'dev_secret_change_me'
  return crypto.createHash('sha256').update(source).digest()
}

export function encryptSecret(value) {
  if (!value || value.startsWith(PREFIX)) return value
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', encryptionKey(), iv)
  const encrypted = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()])
  const tag = cipher.getAuthTag()
  return `${PREFIX}${iv.toString('base64url')}:${tag.toString('base64url')}:${encrypted.toString('base64url')}`
}

export function decryptSecret(value) {
  if (!value || !value.startsWith(PREFIX)) return value
  const parts = value.slice(PREFIX.length).split(':')
  if (parts.length !== 3) throw new Error('Invalid encrypted secret format')
  const [iv, tag, encrypted] = parts.map(part => Buffer.from(part, 'base64url'))
  const decipher = crypto.createDecipheriv('aes-256-gcm', encryptionKey(), iv)
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString('utf8')
}

export function isEncryptedSecret(value) {
  return typeof value === 'string' && value.startsWith(PREFIX)
}
