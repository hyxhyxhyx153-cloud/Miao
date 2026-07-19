import { pool } from '../db/client.js'
import { createEmbedding, cosineSimilarity } from './embeddings.js'

const DEFAULT_MAX_AUTO_MEMORIES = 200
const DEFAULT_SEMANTIC_DUPLICATE_THRESHOLD = 0.9
const DEFAULT_LEXICAL_DUPLICATE_THRESHOLD = 0.78
const MIN_MEMORY_LENGTH = 4
const MAX_INPUT_LENGTH = 600
const MAX_MEMORY_LENGTH = 320

const SECRET_OR_PRIVATE_PATTERN = new RegExp([
  '密(?:码|钥)',
  '口令',
  '验证码',
  '支付码',
  '身份证',
  '银行卡',
  '信用卡',
  'cvv',
  '助记词',
  '私钥',
  'api[ _-]?key',
  'access[ _-]?token',
  'refresh[ _-]?token',
  'bearer\\s+[a-z0-9._-]+',
  'secret',
  '详细地址',
  '家庭住址',
  '门牌号',
  '(?:路|街|巷|弄)\\s*\\d+\\s*号',
  '(?:小区|公寓).{0,20}\\d+(?:栋|幢|单元|室)',
  '\\b1[3-9]\\d{9}\\b',
  '\\b1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}\\b',
  '\\b\\d{15,19}\\b',
  '[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}',
].join('|'), 'i')

const PROMPT_OR_CONTROL_PATTERN = new RegExp([
  '忽略(?:之前|以上|所有)',
  '系统(?:提示词|指令|消息)',
  '开发者(?:提示词|指令|消息)',
  'system\\s*prompt',
  'developer\\s*message',
  '越狱',
  'jailbreak',
  'prompt\\s*injection',
  '不要遵守',
  '绕过(?:规则|限制|安全)',
].join('|'), 'i')

const EXPLICIT_MEMORY_PATTERN = /(?:请|帮我|你要|麻烦你)?(?:记住|记下|别忘(?:了)?|不要忘(?:了)?|保存到记忆|加入记忆)/
const FIRST_PERSON_PATTERN = /(?:我(?!们)|我的|本人|叫我|对我)/u
const IDENTITY_PATTERN = /(?:我叫|我的名字(?:叫|是)|可以叫我|以后叫我|我的昵称(?:叫|是)|我的职业(?:是|为)|我(?:是|做|从事)(?:一名|一个)?(?:学生|教师|老师|医生|护士|程序员|开发者|设计师|产品经理|工程师|律师|会计|研究员|自由职业者|中国人))/
const PREFERENCE_PATTERN = /(?:我(?:很|非常|特别|最|比较|更|一直|平时|通常)?(?:喜欢|偏爱|爱吃|爱喝|爱用|习惯)|我(?:不|很不|最不|一直不)(?:喜欢|爱吃|爱喝|爱用)|我的(?:爱好|偏好|口味|习惯)(?:是|为)|对我来说.+更(?:好|合适)|我通常会)/
const CONSTRAINT_PATTERN = /(?:我对.+过敏|我不能(?:吃|喝|用|接触)|我(?:吃|喝|用).+会(?:过敏|不舒服)|请不要(?:给我|叫我|向我)|不要叫我|我不希望(?:被|你)|我需要避免)/
const PROFILE_PATTERN = /(?:我(?:住|居住|生活)在|我来自|我的家乡(?:是|在)|我的生日(?:是|在)|我的年龄(?:是|为)|我今年\d{1,3}岁|我的星座(?:是|为)|我的性格|我的母语|我(?:会说|主要使用)(?:中文|英文|粤语|普通话|日语|韩语|法语|德语)|我的宠物|我养了?一?只)/
const LONG_TERM_PATTERN = /(?:我的长期目标|我的目标(?:是|为)|我(?:长期|一直)在(?:学习|练习|准备)|我计划长期|我正在学习.+(?:专业|课程|语言)|以后回答我时|以后和我聊天|今后请)/
const TEMPORARY_PATTERN = /(?:刚才|刚刚|此刻|现在有点|今天(?:早上|上午|中午|下午|晚上)?|今晚|明天|后天|这周|本周|下周|周[一二三四五六日天]|(?:上午|下午|晚上)?\d{1,2}[点时]|\d+分钟后|正在(?:吃饭|开会|路上|排队|睡觉)|暂时|临时)/
const THIRD_PARTY_PATTERN = /(?:我(?:朋友|同事|客户|老板|邻居|同学)(?:叫|喜欢|住在|生日)|我的(?:妻子|丈夫|老婆|老公|伴侣|孩子|儿子|女儿|父亲|母亲|爸爸|妈妈)(?:叫|喜欢|住在|生日|电话)|他(?:叫|喜欢|住在|生日)|她(?:叫|喜欢|住在|生日))/
const ASSISTANT_SMALL_TALK_PATTERN = /(?:我(?:喜欢|爱|想)你|我是说|我知道了|我明白了|我也是|我同意|我觉得你|你记住了吗)/

function normalizeWhitespace(value) {
  return value
    .normalize('NFKC')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B-\u200D\uFEFF]/g, '')
    .replace(/\s+/g, ' ')
    .replace(/([\p{Script=Han}]),(?=[\p{Script=Han}])/gu, '$1，')
    .trim()
}

function stripMemoryDirective(value) {
  return value
    .replace(/^(?:请|麻烦你|你要|帮我)?(?:记住|记下|别忘(?:了)?|不要忘(?:了)?|保存到记忆|加入记忆)[：:，,、\s]*/u, '')
    .trim()
}

function splitClauses(value) {
  return (value.match(/[^。！？!?；;\n]+[。！？!?；;]?/gu) || [])
    .filter(segment => !/[?？]\s*$/u.test(segment))
    .map(segment => segment.replace(/[。！!；;]+\s*$/u, ''))
    .map(clause => stripMemoryDirective(clause.replace(/^(?:顺便|另外|还有|对了)[说讲]?[，,、\s]*/u, '').trim()))
    .filter(Boolean)
}

function candidateSignals(clause) {
  const explicit = EXPLICIT_MEMORY_PATTERN.test(clause)
  const firstPerson = FIRST_PERSON_PATTERN.test(` ${clause}`) || /^(?:我|本人|叫我)/u.test(clause)
  const categories = [
    ['identity', IDENTITY_PATTERN],
    ['preference', PREFERENCE_PATTERN],
    ['constraint', CONSTRAINT_PATTERN],
    ['profile', PROFILE_PATTERN],
    ['long_term', LONG_TERM_PATTERN],
  ].filter(([, pattern]) => pattern.test(clause)).map(([name]) => name)
  return { explicit, firstPerson, categories }
}

function rejectClause(clause) {
  if (clause.length < MIN_MEMORY_LENGTH || clause.length > MAX_MEMORY_LENGTH) return 'length'
  if (SECRET_OR_PRIVATE_PATTERN.test(clause)) return 'sensitive'
  if (PROMPT_OR_CONTROL_PATTERN.test(clause)) return 'instruction'
  if (THIRD_PARTY_PATTERN.test(clause)) return 'third_party'
  if (ASSISTANT_SMALL_TALK_PATTERN.test(clause)) return 'small_talk'
  if (/[?？]$/u.test(clause)) return 'question'
  if (TEMPORARY_PATTERN.test(clause) && !/(我的生日|长期|一直|今后|以后)/.test(clause)) return 'temporary'
  if (/```|<script|\b(?:SELECT|INSERT|UPDATE|DELETE)\s+.+\b(?:FROM|INTO|SET)\b/i.test(clause)) return 'code_or_query'
  return null
}

/**
 * Pure, deterministic candidate evaluation. Only durable first-person facts,
 * preferences and constraints are eligible; explicit "remember this" wording
 * alone is intentionally insufficient.
 */
export function evaluateMemoryCandidate(input) {
  if (typeof input !== 'string') return { eligible: false, reason: 'empty' }
  const normalized = normalizeWhitespace(input)
  if (!normalized) return { eligible: false, reason: 'empty' }
  if (normalized.length > MAX_INPUT_LENGTH) return { eligible: false, reason: 'input_too_long' }
  if (SECRET_OR_PRIVATE_PATTERN.test(normalized)) return { eligible: false, reason: 'sensitive' }
  if (PROMPT_OR_CONTROL_PATTERN.test(normalized)) return { eligible: false, reason: 'instruction' }

  const explicitInMessage = EXPLICIT_MEMORY_PATTERN.test(normalized)
  const accepted = []
  const categories = new Set()
  for (const clause of splitClauses(normalized)) {
    const rejection = rejectClause(clause)
    if (rejection) continue
    const signals = candidateSignals(clause)
    const hasDurableCategory = signals.categories.length > 0
    // An explicit instruction may surround a personal fact in another clause,
    // but it never turns an arbitrary command or third-party fact into memory.
    if (!signals.firstPerson || !hasDurableCategory) continue
    accepted.push(clause)
    signals.categories.forEach(category => categories.add(category))
  }

  if (!accepted.length) return { eligible: false, reason: explicitInMessage ? 'not_personal_fact' : 'not_durable' }
  const content = accepted.join('；').slice(0, MAX_MEMORY_LENGTH).trim()
  if (content.length < MIN_MEMORY_LENGTH) return { eligible: false, reason: 'too_short' }
  const confidence = Math.min(1, 0.7 + (explicitInMessage ? 0.15 : 0) + (categories.size > 1 ? 0.1 : 0))
  return {
    eligible: true,
    content,
    summary: content.length > 60 ? `${content.slice(0, 60)}…` : content,
    categories: [...categories],
    confidence,
  }
}

function canonicalizeForComparison(value) {
  return normalizeWhitespace(String(value || ''))
    .toLowerCase()
    .replace(/(?:请|麻烦你|你要|帮我)?(?:记住|记下|别忘(?:了)?|不要忘(?:了)?)/gu, '')
    .replace(/(?:非常|特别|真的|一直|平时|通常|比较|更|很)/gu, '')
    .replace(/[\p{P}\p{S}\s]/gu, '')
}

function bigrams(value) {
  if (value.length < 2) return value ? [value] : []
  const result = []
  for (let index = 0; index < value.length - 1; index += 1) result.push(value.slice(index, index + 2))
  return result
}

function diceCoefficient(left, right) {
  if (!left.length || !right.length) return 0
  const counts = new Map()
  left.forEach(token => counts.set(token, (counts.get(token) || 0) + 1))
  let overlap = 0
  for (const token of right) {
    const count = counts.get(token) || 0
    if (count > 0) {
      overlap += 1
      counts.set(token, count - 1)
    }
  }
  return (2 * overlap) / (left.length + right.length)
}

export function lexicalMemorySimilarity(left, right) {
  const normalizedLeft = canonicalizeForComparison(left)
  const normalizedRight = canonicalizeForComparison(right)
  if (!normalizedLeft || !normalizedRight) return 0
  if (normalizedLeft === normalizedRight) return 1
  const shorter = normalizedLeft.length <= normalizedRight.length ? normalizedLeft : normalizedRight
  const longer = shorter === normalizedLeft ? normalizedRight : normalizedLeft
  if (shorter.length >= 4 && longer.includes(shorter)) return 0.96
  return diceCoefficient(bigrams(normalizedLeft), bigrams(normalizedRight))
}

function hasNegativePolarity(value) {
  return /(?:不喜欢|不爱|不能|不要|不希望|避免|过敏|讨厌)/.test(String(value || ''))
}

export function isDuplicateMemory(candidate, existing, {
  semanticThreshold = DEFAULT_SEMANTIC_DUPLICATE_THRESHOLD,
  lexicalThreshold = DEFAULT_LEXICAL_DUPLICATE_THRESHOLD,
} = {}) {
  const lexicalScore = lexicalMemorySimilarity(candidate.content, existing.content)
  if (lexicalScore >= lexicalThreshold) return { duplicate: true, method: 'lexical', score: lexicalScore }

  const samePolarity = hasNegativePolarity(candidate.content) === hasNegativePolarity(existing.content)
  const semanticScore = candidate.embedding && existing.embedding
    ? cosineSimilarity(candidate.embedding, existing.embedding)
    : -1
  if (samePolarity && semanticScore >= semanticThreshold) {
    return { duplicate: true, method: 'semantic', score: semanticScore }
  }
  return { duplicate: false, method: null, score: Math.max(lexicalScore, semanticScore) }
}

function configuredMaxMemories() {
  const configured = Number(process.env.AUTO_MEMORY_MAX_PER_USER || DEFAULT_MAX_AUTO_MEMORIES)
  return Number.isInteger(configured) && configured > 0 ? configured : DEFAULT_MAX_AUTO_MEMORIES
}

/**
 * Best-effort durable storage. It deliberately resolves to a status object on
 * all failures so chat, regeneration and WeChat delivery can never be broken by
 * the optional memory feature.
 */
export async function considerAutoMemory({
  userId,
  content,
  database = pool,
  embeddingProvider = createEmbedding,
  maxMemories = configuredMaxMemories(),
  logger = console,
} = {}) {
  const candidate = evaluateMemoryCandidate(content)
  if (!candidate.eligible) return { status: 'skipped', reason: candidate.reason }
  if (!userId) return { status: 'skipped', reason: 'missing_user' }

  let embedding = null
  try {
    embedding = await embeddingProvider(candidate.content)
  } catch (error) {
    logger?.warn?.('[auto-memory] embedding unavailable; storing lexical memory only', error?.message || error)
  }

  let client
  let ownsClient = false
  let transactionStarted = false
  try {
    if (typeof database.connect === 'function') {
      client = await database.connect()
      ownsClient = true
    } else {
      client = database
    }
    if (!client?.query) throw new Error('Memory database is unavailable')

    if (ownsClient) {
      await client.query('BEGIN')
      transactionStarted = true
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1)::bigint)', [String(userId)])
    }

    const { rows: existingRows } = await client.query(
      `SELECT id,content,source,embedding
       FROM memories WHERE user_id=$1 AND is_active=true
       ORDER BY created_at DESC`,
      [userId]
    )
    const autoCount = existingRows.filter(memory => memory.source === 'auto').length
    if (autoCount >= maxMemories) {
      if (transactionStarted) await client.query('COMMIT')
      return { status: 'skipped', reason: 'limit_reached', limit: maxMemories }
    }

    const duplicate = existingRows
      .map(memory => ({ memory, result: isDuplicateMemory({ content: candidate.content, embedding }, memory) }))
      .find(item => item.result.duplicate)
    if (duplicate) {
      if (transactionStarted) await client.query('COMMIT')
      return {
        status: 'skipped',
        reason: 'duplicate',
        duplicateId: duplicate.memory.id,
        duplicateMethod: duplicate.result.method,
      }
    }

    const { rows } = await client.query(
      `INSERT INTO memories(user_id,content,summary,source,embedding)
       VALUES($1,$2,$3,'auto',$4) RETURNING id,content,summary,source,created_at`,
      [userId, candidate.content, candidate.summary, embedding ? JSON.stringify(embedding) : null]
    )
    if (transactionStarted) await client.query('COMMIT')
    return { status: 'stored', memory: rows[0] }
  } catch (error) {
    if (transactionStarted) await client.query('ROLLBACK').catch(() => {})
    logger?.warn?.('[auto-memory] storage skipped after internal failure', error?.message || error)
    return { status: 'failed', reason: 'internal_error' }
  } finally {
    if (ownsClient) client?.release?.()
  }
}

export function queueAutoMemory(options) {
  setImmediate(() => {
    considerAutoMemory(options).catch(error => {
      options?.logger?.warn?.('[auto-memory] unexpected queued failure', error?.message || error)
    })
  })
}
