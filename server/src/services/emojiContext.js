export function cleanEmojiText(value, maxLength) {
  return String(value || '').replace(/\s+/g, ' ').trim().slice(0, maxLength)
}

export function buildEmojiAssistantContent(emoji) {
  const tag = cleanEmojiText(emoji?.emotion_tag, 32) || '未标注'
  const description = cleanEmojiText(emoji?.description, 256) || '无文字描述'
  return `[AI发送了表情包] 标签：${tag}；描述：${description}`
}

export function buildEmojiUserContent(emoji) {
  const tag = cleanEmojiText(emoji?.emotion_tag, 32) || '未标注'
  const description = cleanEmojiText(emoji?.description, 256) || '无文字描述'
  return `用户发送了一个表情包。\n表情标签：${tag}\n表情描述：${description}`
}

export function buildEmojiPreview(emoji) {
  const tag = cleanEmojiText(emoji?.emotion_tag, 32) || '表情'
  const description = cleanEmojiText(emoji?.description, 80)
  return description ? `[${tag}表情] ${description}` : `[${tag}表情]`
}

export function toModelHistoryMessage(message) {
  return {
    role: message.role,
    content: message.content,
    mediaUrls: message.content_type === 'image' && Array.isArray(message.media_urls)
      ? message.media_urls
      : [],
  }
}
