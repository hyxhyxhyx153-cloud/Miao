import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ensurePersonaEmotionPrompt,
  hasPersonaEmotionMeta,
  hasPersonaImageMeta,
  parsePersonaResponseMeta,
  PERSONA_EMOTIONS,
} from '../services/personaPrompt.js'

test('persona prompt appends the canonical emotion metadata protocol', () => {
  const result = ensurePersonaEmotionPrompt('  你是一位温柔的朋友。  ')
  assert.ok(result.startsWith('你是一位温柔的朋友。\n\n'))
  assert.match(result, /<!--\{"emotion":"gentle","action":null,"generateImage":false,"imagePrompt":null\}-->/)
  for (const emotion of PERSONA_EMOTIONS) assert.match(result, new RegExp(`\\b${emotion}\\b`))
  assert.match(result, /generateImage 必须为 true/)
})

test('persona prompt keeps an existing extended metadata example without duplication', () => {
  const prompt = '保持活泼。末尾输出 <!--{"emotion":"happy","action":null,"generateImage":false,"imagePrompt":null}-->'
  const result = ensurePersonaEmotionPrompt(`  ${prompt}  `)
  assert.equal(result, prompt)
  assert.equal(result.match(/<!--/g)?.length, 1)
  assert.equal(hasPersonaEmotionMeta(result), true)
  assert.equal(hasPersonaImageMeta(result), true)
})

test('persona prompt replaces a legacy protocol with the canonical image-aware protocol', () => {
  const result = ensurePersonaEmotionPrompt('保持温柔。使用 <!--{"emotion":"happy","action":null}-->')
  assert.equal(hasPersonaEmotionMeta(result), true)
  assert.equal(hasPersonaImageMeta(result), true)
  assert.equal(result.match(/<!--/g)?.length, 1)
  assert.match(result, /^保持温柔。使用/)
})

test('persona response metadata accepts whitespace and validates emotion and action', () => {
  assert.deepEqual(
    parsePersonaResponseMeta('你好\n<!-- {"emotion":"excited","action":"挥手"} -->'),
    {
      emotion: 'excited',
      action: '挥手',
      generateImage: false,
      imagePrompt: null,
      cleanText: '你好',
    }
  )
  assert.deepEqual(
    parsePersonaResponseMeta('你好<!--{"emotion":"joy","action":42}-->'),
    {
      emotion: 'gentle',
      action: null,
      generateImage: false,
      imagePrompt: null,
      cleanText: '你好',
    }
  )
})

test('persona response metadata ignores unrelated JSON comments and keeps scanning', () => {
  assert.deepEqual(
    parsePersonaResponseMeta('正文<!--{"foo":"bar"}-->继续<!--{"emotion":"happy","action":null}-->'),
    {
      emotion: 'happy',
      action: null,
      generateImage: false,
      imagePrompt: null,
      cleanText: '正文<!--{"foo":"bar"}-->继续',
    }
  )
  assert.deepEqual(
    parsePersonaResponseMeta('正文<!--{"foo":"bar"}-->'),
    {
      emotion: 'gentle',
      action: null,
      generateImage: false,
      imagePrompt: null,
      cleanText: '正文<!--{"foo":"bar"}-->',
    }
  )
})

test('persona response metadata recognizes unsupported emotions before downgrading them', () => {
  assert.deepEqual(
    parsePersonaResponseMeta('正文<!--{"emotion":"joy","action":"跳舞"}-->'),
    {
      emotion: 'gentle',
      action: '跳舞',
      generateImage: false,
      imagePrompt: null,
      cleanText: '正文',
    }
  )
})

test('persona response metadata returns a normalized image request from the main reply', () => {
  assert.deepEqual(
    parsePersonaResponseMeta('马上为你画。<!--{"emotion":"excited","action":"拿起画笔","generateImage":true,"imagePrompt":"  一只橘猫坐在月光窗台，温暖水彩插画，主体完整，柔和侧光  "}-->'),
    {
      emotion: 'excited',
      action: '拿起画笔',
      generateImage: true,
      imagePrompt: '一只橘猫坐在月光窗台，温暖水彩插画，主体完整，柔和侧光',
      cleanText: '马上为你画。',
    }
  )
})

test('persona response metadata refuses image generation when the prompt is absent', () => {
  assert.deepEqual(
    parsePersonaResponseMeta('我来画。<!--{"emotion":"happy","action":null,"generateImage":true,"imagePrompt":"  "}-->'),
    {
      emotion: 'happy',
      action: null,
      generateImage: false,
      imagePrompt: null,
      cleanText: '我来画。',
    }
  )
})
