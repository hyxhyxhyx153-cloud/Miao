export const PERSONA_EMOTIONS = Object.freeze([
  'happy',
  'excited',
  'curious',
  'shy',
  'embarrassed',
  'caring',
  'gentle',
  'playful',
  'thinking',
  'surprised',
  'sad',
  'nervous',
  'proud',
  'sleepy',
  'angry',
])

const emotionSet = new Set(PERSONA_EMOTIONS)
const metaCommentPattern = /<!--\s*([\s\S]*?)\s*-->/g

export const PERSONA_EMOTION_META_INSTRUCTION = `每次回复末尾必须追加且只追加一个 JSON 元数据 HTML 注释，格式：<!--{"emotion":"gentle","action":null,"generateImage":false,"imagePrompt":null}-->。
- emotion 只能是以下标签之一：${PERSONA_EMOTIONS.join(', ')}。
- action 必须是描述当前动作的简短字符串或 null。
- 当用户本轮明确要求画图、生成图片、制作视觉成品或用图片呈现场景时，generateImage 必须为 true；否则必须为 false。不要因为用户仅提到、分析或询问图片生成能力就设为 true。
- generateImage 为 true 时，imagePrompt 必须是已补全的、自包含且可直接交给图片模型的提示词，忠实保留用户要求，并写清主体、场景、关键细节、构图视角、媒介或风格、光线氛围、文字要求、必须保留项和禁止项；不得留空。generateImage 为 false 时，imagePrompt 必须为 null。
- 不要在正文中解释或展示这段 JSON，也不要把图片提示词写进正文。`

export const DEFAULT_PERSONA_SYSTEM_PROMPT = `你是一个友好、自然的聊天伙伴。${PERSONA_EMOTION_META_INSTRUCTION}`

export function isPersonaEmotion(emotion) {
  return typeof emotion === 'string' && emotionSet.has(emotion)
}

export function hasPersonaEmotionMeta(prompt) {
  for (const match of String(prompt || '').matchAll(metaCommentPattern)) {
    try {
      const meta = JSON.parse(match[1])
      if (isPersonaEmotion(meta.emotion) && Object.prototype.hasOwnProperty.call(meta, 'action')) return true
    } catch {
      // Ignore malformed examples and append the canonical protocol below.
    }
  }
  return false
}

export function hasPersonaImageMeta(prompt) {
  for (const match of String(prompt || '').matchAll(metaCommentPattern)) {
    try {
      const meta = JSON.parse(match[1])
      const imageFieldsAreValid = meta.generateImage === false
        ? meta.imagePrompt === null
        : meta.generateImage === true
          && typeof meta.imagePrompt === 'string'
          && Boolean(meta.imagePrompt.trim())
      if (
        isPersonaEmotion(meta.emotion)
        && Object.prototype.hasOwnProperty.call(meta, 'action')
        && imageFieldsAreValid
      ) return true
    } catch {
      // Ignore malformed examples and append the canonical protocol below.
    }
  }
  return false
}

function removeLegacyPersonaMeta(prompt) {
  return String(prompt || '').replace(metaCommentPattern, (comment, json) => {
    try {
      const meta = JSON.parse(json)
      if (
        meta
        && typeof meta === 'object'
        && !Array.isArray(meta)
        && Object.prototype.hasOwnProperty.call(meta, 'emotion')
        && Object.prototype.hasOwnProperty.call(meta, 'action')
      ) return ''
    } catch {
      // Preserve unrelated or malformed HTML comments from the persona prompt.
    }
    return comment
  }).trim()
}

export function ensurePersonaEmotionPrompt(prompt) {
  const normalized = String(prompt || '').trim()
  if (hasPersonaImageMeta(normalized)) return normalized
  const withoutLegacyMeta = removeLegacyPersonaMeta(normalized)
  return withoutLegacyMeta
    ? `${withoutLegacyMeta}\n\n${PERSONA_EMOTION_META_INSTRUCTION}`
    : DEFAULT_PERSONA_SYSTEM_PROMPT
}

export function parsePersonaResponseMeta(text) {
  const source = String(text || '')
  let parsed = null
  const recognizedComments = []
  for (const match of source.matchAll(metaCommentPattern)) {
    try {
      const meta = JSON.parse(match[1])
      if (
        !meta
        || typeof meta !== 'object'
        || Array.isArray(meta)
        || !Object.prototype.hasOwnProperty.call(meta, 'emotion')
        || !Object.prototype.hasOwnProperty.call(meta, 'action')
      ) continue
      const action = typeof meta.action === 'string' && meta.action.trim()
        ? meta.action.trim()
        : null
      const requestedImagePrompt = typeof meta.imagePrompt === 'string'
        ? meta.imagePrompt.trim().slice(0, 12_000)
        : ''
      const generateImage = meta.generateImage === true && Boolean(requestedImagePrompt)
      recognizedComments.push(match[0])
      parsed = {
        emotion: isPersonaEmotion(meta.emotion) ? meta.emotion : 'gentle',
        action,
        generateImage,
        imagePrompt: generateImage ? requestedImagePrompt : null,
      }
    } catch {
      // Continue in case a later HTML comment contains valid JSON metadata.
    }
  }
  if (parsed) {
    return {
      ...parsed,
      cleanText: recognizedComments.reduce(
        (cleanText, comment) => cleanText.replace(comment, ''),
        source,
      ).trim(),
    }
  }
  return {
    emotion: 'gentle',
    action: null,
    generateImage: false,
    imagePrompt: null,
    cleanText: source,
  }
}
