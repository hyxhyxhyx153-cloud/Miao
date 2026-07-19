import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const PROVIDER_ENV_NAMES = Object.freeze({
  anthropic: 'ANTHROPIC_API_KEY',
  openai: 'OPENAI_API_KEY',
  'gpt-image': 'GPT_IMAGE_API_KEY',
  deepseek: 'DEEPSEEK_API_KEY',
  qwen: 'QWEN_API_KEY',
  zhipu: 'ZHIPU_API_KEY',
})

const defaultEnvPath = process.env.DOTENV_CONFIG_PATH
  ? resolve(process.cwd(), process.env.DOTENV_CONFIG_PATH)
  : fileURLToPath(new URL('../../.env', import.meta.url))

const writeQueues = new Map()

export function getProviderEnvName(provider) {
  return PROVIDER_ENV_NAMES[provider] || null
}

export function setProviderApiKeyRuntime(provider, apiKey) {
  const envName = getProviderEnvName(provider)
  if (!envName) throw new Error(`Unsupported API key provider: ${provider}`)
  process.env[envName] = typeof apiKey === 'string' ? apiKey : ''
}

function encodeEnvValue(value) {
  if (/^[A-Za-z0-9_./:+-]*$/.test(value)) return value

  return `"${value
    .replace(/\\/g, '\\\\')
    .replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n')
    .replace(/"/g, '\\"')}"`
}

async function updateEnvFile(envPath, envName, value) {
  let content = ''
  try {
    content = await readFile(envPath, 'utf8')
  } catch (err) {
    if (err.code !== 'ENOENT') throw err
  }

  const newline = content.includes('\r\n') ? '\r\n' : '\n'
  const encodedValue = encodeEnvValue(value)
  const assignment = new RegExp(
    `^([ \\t]*(?:export[ \\t]+)?${envName}[ \\t]*=)[^\\r\\n]*(\\r?)$`,
    'm'
  )

  let updated
  if (assignment.test(content)) {
    updated = content.replace(
      assignment,
      (_match, prefix, carriageReturn) => `${prefix}${encodedValue}${carriageReturn}`
    )
  } else {
    const separator = content.length > 0 && !content.endsWith('\n') && !content.endsWith('\r')
      ? newline
      : ''
    updated = `${content}${separator}${envName}=${encodedValue}${newline}`
  }

  await writeFile(envPath, updated, 'utf8')
}

export async function writeProviderApiKeyToEnv(provider, apiKey, options = {}) {
  const envName = getProviderEnvName(provider)
  if (!envName) throw new Error(`Unsupported API key provider: ${provider}`)

  const envPath = options.envPath || defaultEnvPath
  const value = typeof apiKey === 'string' ? apiKey : ''
  setProviderApiKeyRuntime(provider, value)
  const previous = writeQueues.get(envPath) || Promise.resolve()
  const pending = previous
    .catch(() => {})
    .then(() => updateEnvFile(envPath, envName, value))

  writeQueues.set(envPath, pending)
  try {
    await pending
  } finally {
    if (writeQueues.get(envPath) === pending) writeQueues.delete(envPath)
  }
}
