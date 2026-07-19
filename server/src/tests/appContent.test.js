import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { isValidApkArchive } from '../routes/appContent.js'
import {
  announcementToDto,
  appReleaseFromSettings,
  appReleaseToSettingEntries,
  isApkZipMagic,
  LEGAL_DOCUMENT_MAX_LENGTH,
  legalFromSettings,
  legalToSettingEntries,
  validateAnnouncement,
  validateAppRelease,
  validateLegal,
} from '../services/appContent.js'

test('app release keeps legacy fields and calculates forced updates by version code', () => {
  const settings = {
    latest_android_version: '2.4.0',
    latest_android_version_code: 24,
    android_min_supported_version_code: 20,
    android_force_update: true,
    android_release_notes: 'Important fixes',
    android_download_url: 'https://example.com/miao.apk',
  }
  assert.deepEqual(appReleaseFromSettings(settings, 24), {
    latestVersion: '2.4.0',
    latestVersionCode: 24,
    minSupportedVersionCode: 20,
    forceUpdate: false,
    releaseNotes: 'Important fixes',
    downloadUrl: 'https://example.com/miao.apk',
    updatedAt: null,
  })
  assert.equal(appReleaseFromSettings(settings, 23).forceUpdate, true)
  assert.equal(appReleaseFromSettings({ ...settings, android_force_update: false }, 19).forceUpdate, true)
  assert.equal(appReleaseFromSettings({ ...settings, android_force_update: false }, 23).forceUpdate, false)
})

test('release validation is strict and prevents an update dead end', () => {
  const current = {
    latestVersionCode: 10,
    minSupportedVersionCode: 1,
    forceUpdate: false,
    downloadUrl: '',
  }
  assert.throws(
    () => validateAppRelease({ forceUpdate: true }, current),
    /downloadUrl is required/
  )
  assert.throws(
    () => validateAppRelease({ minSupportedVersionCode: 2 }, current),
    /downloadUrl is required/
  )
  assert.throws(
    () => validateAppRelease({ latestVersionCode: 9, minSupportedVersionCode: 10 }, current),
    /cannot exceed/
  )
  assert.throws(() => validateAppRelease({ surprise: true }, current), /Unknown field/)

  const value = validateAppRelease({
    latestVersion: '2.0',
    latestVersionCode: 20,
    minSupportedVersionCode: 10,
    forceUpdate: true,
    releaseNotes: 'Release notes',
    downloadUrl: '/api/v1/uploads/releases/miao-2.apk',
  }, current)
  assert.deepEqual(Object.fromEntries(appReleaseToSettingEntries(value)), {
    latest_android_version: '2.0',
    latest_android_version_code: 20,
    android_min_supported_version_code: 10,
    android_force_update: true,
    android_release_notes: 'Release notes',
    android_download_url: '/api/v1/uploads/releases/miao-2.apk',
  })
})

test('legal settings expose canonical client fields and accept compatibility aliases', () => {
  assert.deepEqual(legalFromSettings({
    privacy_policy_content: 'Privacy',
    user_agreement_content: 'Agreement',
    legal_version: '2026.1',
  }), {
    privacyPolicy: 'Privacy',
    userAgreement: 'Agreement',
    version: '2026.1',
    updatedAt: null,
  })
  const canonical = validateLegal({ privacyPolicy: ' New privacy ', userAgreement: 'Terms', version: '2' })
  assert.deepEqual(canonical, { privacyPolicy: 'New privacy', userAgreement: 'Terms', version: '2' })
  const aliases = validateLegal({
    privacyPolicyContent: 'Old privacy field',
    userAgreementContent: 'Old terms field',
    legalVersion: 'legacy-2',
  })
  assert.deepEqual(Object.fromEntries(legalToSettingEntries(aliases)), {
    privacy_policy_content: 'Old privacy field',
    user_agreement_content: 'Old terms field',
    legal_version: 'legacy-2',
  })
})

test('legal settings preserve long documents up to the configured boundary', () => {
  const longDocument = '隐'.repeat(LEGAL_DOCUMENT_MAX_LENGTH)
  const value = validateLegal({ privacyPolicy: longDocument })
  assert.equal(value.privacyPolicy.length, LEGAL_DOCUMENT_MAX_LENGTH)
  assert.equal(
    Object.fromEntries(legalToSettingEntries(value)).privacy_policy_content,
    longDocument
  )
  assert.throws(
    () => validateLegal({ privacyPolicy: `${longDocument}私` }),
    /privacyPolicy is too long/
  )
})

test('announcement validation supports scheduling and camelCase DTOs', () => {
  const value = validateAnnouncement({
    title: 'Maintenance',
    content: 'Service will be unavailable briefly.',
    type: 'maintenance',
    isActive: true,
    isPinned: true,
    startsAt: '2026-07-16T02:00:00Z',
    endsAt: '2026-07-16T03:00:00Z',
  })
  assert.equal(value.startsAt, '2026-07-16T02:00:00.000Z')
  assert.throws(() => validateAnnouncement({
    title: 'Bad schedule',
    content: 'Invalid',
    startsAt: '2026-07-16T03:00:00Z',
    endsAt: '2026-07-16T02:00:00Z',
  }), /endsAt must be later/)
  assert.throws(() => validateAnnouncement({ title: 'x', content: 'y', type: 'urgent' }), /type must be/)

  const dto = announcementToDto({
    id: 'notice-1',
    title: 'Hello',
    content: 'World',
    type: 'info',
    is_active: true,
    is_pinned: false,
    starts_at: null,
    ends_at: null,
    created_at: new Date('2026-07-16T00:00:00Z'),
    updated_at: new Date('2026-07-16T01:00:00Z'),
  })
  assert.equal(dto.isActive, true)
  assert.equal(dto.createdAt, '2026-07-16T00:00:00.000Z')
})

test('APK signature validation accepts ZIP headers only', () => {
  assert.equal(isApkZipMagic(Buffer.from([0x50, 0x4b, 0x03, 0x04])), true)
  assert.equal(isApkZipMagic(Buffer.from([0x50, 0x4b, 0x05, 0x06])), true)
  assert.equal(isApkZipMagic(Buffer.from('not an apk')), false)
  assert.equal(isApkZipMagic(Buffer.alloc(3)), false)
})

function singleEntryZip(entryName) {
  const name = Buffer.from(entryName)
  const localHeader = Buffer.alloc(30)
  localHeader.writeUInt32LE(0x04034b50, 0)
  localHeader.writeUInt16LE(20, 4)
  localHeader.writeUInt16LE(name.length, 26)

  const centralHeader = Buffer.alloc(46)
  centralHeader.writeUInt32LE(0x02014b50, 0)
  centralHeader.writeUInt16LE(20, 4)
  centralHeader.writeUInt16LE(20, 6)
  centralHeader.writeUInt16LE(name.length, 28)

  const centralDirectory = Buffer.concat([centralHeader, name])
  const centralOffset = localHeader.length + name.length
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(1, 8)
  eocd.writeUInt16LE(1, 10)
  eocd.writeUInt32LE(centralDirectory.length, 12)
  eocd.writeUInt32LE(centralOffset, 16)
  return Buffer.concat([localHeader, name, centralDirectory, eocd])
}

test('APK archive validation requires a complete ZIP with AndroidManifest.xml', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'miao-apk-test-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const validPath = join(directory, 'valid.apk')
  const invalidPath = join(directory, 'invalid.apk')
  await writeFile(validPath, singleEntryZip('AndroidManifest.xml'))
  await writeFile(invalidPath, singleEntryZip('README.txt'))
  const validStat = await stat(validPath)
  const invalidStat = await stat(invalidPath)
  assert.equal(await isValidApkArchive(validPath, validStat.size), true)
  assert.equal(await isValidApkArchive(invalidPath, invalidStat.size), false)
})
