export const APP_RELEASE_SETTING_KEYS = [
  'latest_android_version',
  'latest_android_version_code',
  'android_min_supported_version_code',
  'android_force_update',
  'android_release_notes',
  'android_download_url',
]

export const LEGAL_SETTING_KEYS = [
  'privacy_policy_content',
  'user_agreement_content',
  'legal_version',
]

// Keep this limit aligned with the administration editor. system_settings.value
// is JSONB and does not truncate strings, but an explicit application limit
// prevents an authenticated request from consuming unbounded memory.
export const LEGAL_DOCUMENT_MAX_LENGTH = 200_000

const ANNOUNCEMENT_TYPES = new Set(['info', 'warning', 'maintenance'])
const RELEASE_FIELDS = new Set([
  'latestVersion',
  'latestVersionCode',
  'minSupportedVersionCode',
  'forceUpdate',
  'releaseNotes',
  'downloadUrl',
])
const LEGAL_FIELDS = new Set([
  'privacyPolicy',
  'userAgreement',
  'version',
  'privacyPolicyContent',
  'userAgreementContent',
  'legalVersion',
])
const ANNOUNCEMENT_FIELDS = new Set([
  'title',
  'content',
  'type',
  'isActive',
  'isPinned',
  'startsAt',
  'endsAt',
])

function assertPlainObject(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be an object`)
  }
}

function assertKnownFields(value, allowed) {
  const unknown = Object.keys(value).filter(key => !allowed.has(key))
  if (unknown.length) throw new Error(`Unknown field: ${unknown[0]}`)
  if (!Object.keys(value).length) throw new Error('At least one field is required')
}

function requiredString(value, name, maxLength, { allowEmpty = false } = {}) {
  if (typeof value !== 'string') throw new Error(`${name} must be a string`)
  const normalized = value.trim()
  if (!allowEmpty && !normalized) throw new Error(`${name} cannot be empty`)
  if (value.length > maxLength) throw new Error(`${name} is too long`)
  return allowEmpty ? value : normalized
}

function positiveVersionCode(value, name) {
  if (!Number.isInteger(value) || value < 1 || value > 2147483647) {
    throw new Error(`${name} must be an integer between 1 and 2147483647`)
  }
  return value
}

function optionalDate(value, name) {
  if (value === null) return null
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${name} must be an ISO date or null`)
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) throw new Error(`${name} must be an ISO date or null`)
  return date.toISOString()
}

function iso(value) {
  if (!value) return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

function settingInteger(value, fallback) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isInteger(parsed) && parsed >= 1 ? parsed : fallback
}

export function settingsRowsToObject(rows) {
  return Object.fromEntries(rows.map(row => [row.key, row.value]))
}

export function appReleaseFromSettings(settings, currentVersionCode = null, updatedAt = null) {
  const latestVersionCode = settingInteger(settings.latest_android_version_code, 1)
  const minSupportedVersionCode = settingInteger(settings.android_min_supported_version_code, 1)
  const parsedCurrentVersionCode = Number(currentVersionCode)
  const isCurrentVersionKnown = Number.isInteger(parsedCurrentVersionCode) && parsedCurrentVersionCode >= 1
  const configuredForceUpdate = settings.android_force_update === true
  const forceUpdate = isCurrentVersionKnown
    ? parsedCurrentVersionCode < latestVersionCode
      && (configuredForceUpdate || parsedCurrentVersionCode < minSupportedVersionCode)
    : configuredForceUpdate

  return {
    latestVersion: typeof settings.latest_android_version === 'string'
      ? settings.latest_android_version
      : '1.0',
    latestVersionCode,
    minSupportedVersionCode,
    forceUpdate,
    releaseNotes: typeof settings.android_release_notes === 'string'
      ? settings.android_release_notes
      : '',
    downloadUrl: typeof settings.android_download_url === 'string'
      ? settings.android_download_url
      : '',
    updatedAt: iso(updatedAt),
  }
}

export function validateAppRelease(body, current = null) {
  assertPlainObject(body, 'Release settings')
  assertKnownFields(body, RELEASE_FIELDS)
  const result = {}

  if (Object.hasOwn(body, 'latestVersion')) {
    result.latestVersion = requiredString(body.latestVersion, 'latestVersion', 64)
  }
  if (Object.hasOwn(body, 'latestVersionCode')) {
    result.latestVersionCode = positiveVersionCode(body.latestVersionCode, 'latestVersionCode')
  }
  if (Object.hasOwn(body, 'minSupportedVersionCode')) {
    result.minSupportedVersionCode = positiveVersionCode(body.minSupportedVersionCode, 'minSupportedVersionCode')
  }
  if (Object.hasOwn(body, 'forceUpdate')) {
    if (typeof body.forceUpdate !== 'boolean') throw new Error('forceUpdate must be a boolean')
    result.forceUpdate = body.forceUpdate
  }
  if (Object.hasOwn(body, 'releaseNotes')) {
    result.releaseNotes = requiredString(body.releaseNotes, 'releaseNotes', 20000, { allowEmpty: true })
  }
  if (Object.hasOwn(body, 'downloadUrl')) {
    const downloadUrl = requiredString(body.downloadUrl, 'downloadUrl', 2048, { allowEmpty: true }).trim()
    if (downloadUrl && !/^https?:\/\/[^\s]+$/i.test(downloadUrl) && !/^\/api\/v1\/uploads\/releases\/[A-Za-z0-9._-]+\.apk$/i.test(downloadUrl)) {
      throw new Error('downloadUrl must be an HTTP(S) URL or a release upload path')
    }
    result.downloadUrl = downloadUrl
  }

  const latest = result.latestVersionCode ?? current?.latestVersionCode
  const minimum = result.minSupportedVersionCode ?? current?.minSupportedVersionCode
  if (Number.isInteger(latest) && Number.isInteger(minimum) && minimum > latest) {
    throw new Error('minSupportedVersionCode cannot exceed latestVersionCode')
  }
  const forceUpdate = result.forceUpdate ?? current?.forceUpdate
  const downloadUrl = result.downloadUrl ?? current?.downloadUrl
  if ((forceUpdate === true || (Number.isInteger(minimum) && minimum > 1)) && !downloadUrl) {
    throw new Error('downloadUrl is required when an update can be forced')
  }
  return result
}

export function appReleaseToSettingEntries(value) {
  const mapping = {
    latestVersion: 'latest_android_version',
    latestVersionCode: 'latest_android_version_code',
    minSupportedVersionCode: 'android_min_supported_version_code',
    forceUpdate: 'android_force_update',
    releaseNotes: 'android_release_notes',
    downloadUrl: 'android_download_url',
  }
  return Object.entries(value).map(([key, settingValue]) => [mapping[key], settingValue])
}

export function legalFromSettings(settings, updatedAt = null) {
  return {
    privacyPolicy: typeof settings.privacy_policy_content === 'string'
      ? settings.privacy_policy_content
      : '',
    userAgreement: typeof settings.user_agreement_content === 'string'
      ? settings.user_agreement_content
      : '',
    version: typeof settings.legal_version === 'string' ? settings.legal_version : '1.0',
    updatedAt: iso(updatedAt),
  }
}

export function validateLegal(body) {
  assertPlainObject(body, 'Legal settings')
  assertKnownFields(body, LEGAL_FIELDS)
  if (Object.hasOwn(body, 'privacyPolicy') && Object.hasOwn(body, 'privacyPolicyContent')) {
    throw new Error('Use either privacyPolicy or privacyPolicyContent, not both')
  }
  if (Object.hasOwn(body, 'userAgreement') && Object.hasOwn(body, 'userAgreementContent')) {
    throw new Error('Use either userAgreement or userAgreementContent, not both')
  }
  if (Object.hasOwn(body, 'version') && Object.hasOwn(body, 'legalVersion')) {
    throw new Error('Use either version or legalVersion, not both')
  }
  const result = {}
  if (Object.hasOwn(body, 'privacyPolicy') || Object.hasOwn(body, 'privacyPolicyContent')) {
    result.privacyPolicy = requiredString(
      body.privacyPolicy ?? body.privacyPolicyContent,
      'privacyPolicy',
      LEGAL_DOCUMENT_MAX_LENGTH
    )
  }
  if (Object.hasOwn(body, 'userAgreement') || Object.hasOwn(body, 'userAgreementContent')) {
    result.userAgreement = requiredString(
      body.userAgreement ?? body.userAgreementContent,
      'userAgreement',
      LEGAL_DOCUMENT_MAX_LENGTH
    )
  }
  if (Object.hasOwn(body, 'version') || Object.hasOwn(body, 'legalVersion')) {
    result.version = requiredString(body.version ?? body.legalVersion, 'version', 64)
  }
  return result
}

export function legalToSettingEntries(value) {
  const mapping = {
    privacyPolicy: 'privacy_policy_content',
    userAgreement: 'user_agreement_content',
    version: 'legal_version',
  }
  return Object.entries(value).map(([key, settingValue]) => [mapping[key], settingValue])
}

export function announcementToDto(row) {
  return {
    id: row.id,
    title: row.title,
    content: row.content,
    type: row.type,
    isActive: row.is_active,
    isPinned: row.is_pinned,
    startsAt: iso(row.starts_at),
    endsAt: iso(row.ends_at),
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
  }
}

export function validateAnnouncement(body, { partial = false, current = null } = {}) {
  assertPlainObject(body, 'Announcement')
  assertKnownFields(body, ANNOUNCEMENT_FIELDS)
  const result = {}

  if (!partial || Object.hasOwn(body, 'title')) {
    result.title = requiredString(body.title, 'title', 128)
  }
  if (!partial || Object.hasOwn(body, 'content')) {
    result.content = requiredString(body.content, 'content', 50000)
  }
  if (Object.hasOwn(body, 'type')) {
    if (typeof body.type !== 'string' || !ANNOUNCEMENT_TYPES.has(body.type)) {
      throw new Error('type must be info, warning or maintenance')
    }
    result.type = body.type
  } else if (!partial) {
    result.type = 'info'
  }
  for (const key of ['isActive', 'isPinned']) {
    if (Object.hasOwn(body, key)) {
      if (typeof body[key] !== 'boolean') throw new Error(`${key} must be a boolean`)
      result[key] = body[key]
    } else if (!partial) {
      result[key] = key === 'isActive'
    }
  }
  for (const key of ['startsAt', 'endsAt']) {
    if (Object.hasOwn(body, key)) result[key] = optionalDate(body[key], key)
    else if (!partial) result[key] = null
  }

  const startsAt = result.startsAt !== undefined ? result.startsAt : current?.startsAt
  const endsAt = result.endsAt !== undefined ? result.endsAt : current?.endsAt
  if (startsAt && endsAt && new Date(endsAt).getTime() <= new Date(startsAt).getTime()) {
    throw new Error('endsAt must be later than startsAt')
  }
  return result
}

export function isApkZipMagic(bytes) {
  if (!bytes || bytes.length < 4) return false
  return bytes[0] === 0x50 && bytes[1] === 0x4b && (
    (bytes[2] === 0x03 && bytes[3] === 0x04)
    || (bytes[2] === 0x05 && bytes[3] === 0x06)
    || (bytes[2] === 0x07 && bytes[3] === 0x08)
  )
}
