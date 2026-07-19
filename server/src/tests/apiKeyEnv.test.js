import { afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import {
  getProviderEnvName,
  writeProviderApiKeyToEnv,
} from '../config/apiKeyEnv.js'

const tempDirectories = []

afterEach(async () => {
  await Promise.all(tempDirectories.splice(0).map(path => rm(path, { recursive: true, force: true })))
})

async function createEnvFile(content) {
  const directory = await mkdtemp(join(tmpdir(), 'miao-api-key-env-'))
  tempDirectories.push(directory)
  const envPath = join(directory, '.env')
  await writeFile(envPath, content, 'utf8')
  return envPath
}

test('maps supported providers to their environment variable names', () => {
  assert.equal(getProviderEnvName('anthropic'), 'ANTHROPIC_API_KEY')
  assert.equal(getProviderEnvName('openai'), 'OPENAI_API_KEY')
  assert.equal(getProviderEnvName('gpt-image'), 'GPT_IMAGE_API_KEY')
  assert.equal(getProviderEnvName('unknown'), null)
})

test('updates an existing key without changing the rest of a CRLF env file', async () => {
  const envPath = await createEnvFile(
    'PORT=3000\r\nOPENAI_API_KEY=old-key\r\nREDIS_URL=redis://localhost:6379\r\n'
  )

  await writeProviderApiKeyToEnv('openai', 'sk-new-key', { envPath })

  assert.equal(
    await readFile(envPath, 'utf8'),
    'PORT=3000\r\nOPENAI_API_KEY=sk-new-key\r\nREDIS_URL=redis://localhost:6379\r\n'
  )
  assert.equal(process.env.OPENAI_API_KEY, 'sk-new-key')
})

test('appends a missing provider key and escapes dotenv-sensitive values', async () => {
  const envPath = await createEnvFile('PORT=3000')

  await writeProviderApiKeyToEnv('qwen', 'key with # and "quotes"', { envPath })

  assert.equal(
    await readFile(envPath, 'utf8'),
    'PORT=3000\nQWEN_API_KEY="key with # and \\"quotes\\""\n'
  )
})

test('clears the configured value when no active database key remains', async () => {
  const envPath = await createEnvFile('ANTHROPIC_API_KEY=sk-old\n')

  await writeProviderApiKeyToEnv('anthropic', '', { envPath })

  assert.equal(await readFile(envPath, 'utf8'), 'ANTHROPIC_API_KEY=\n')
  assert.equal(process.env.ANTHROPIC_API_KEY, '')
})

test('updates the runtime key even when the env file cannot be written', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'miao-api-key-env-readonly-'))
  tempDirectories.push(directory)

  await assert.rejects(
    writeProviderApiKeyToEnv('openai', 'sk-runtime-only', { envPath: directory })
  )
  assert.equal(process.env.OPENAI_API_KEY, 'sk-runtime-only')
})
