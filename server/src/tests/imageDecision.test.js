import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildDirectImagePlan,
  buildImagePlannerPrompt,
  isExplicitImageGenerationRequest,
  isImageGenerationDenial,
  normalizePostImageReply,
  parseImagePlan,
  shouldRunImageGenerationPlanner,
} from '../services/imageDecision.js'
import {
  imageQualityAttempts,
  isRetryableImageFailure,
  validateReferenceImageUrls,
} from '../services/imageGeneration.js'

test('parses a positive image plan and normalizes its fields', () => {
  const plan = parseImagePlan(JSON.stringify({
    should_generate: true,
    prompt: 'A warm watercolor portrait of the character',
    caption: '给你画好啦',
    aspect_ratio: 'portrait',
  }))
  assert.deepEqual(plan, {
    prompt: 'A warm watercolor portrait of the character',
    caption: '给你画好啦',
    aspectRatio: 'portrait',
  })
})

test('does not generate for negative, malformed, or prompt-less decisions', () => {
  assert.equal(parseImagePlan('{"should_generate":false}'), null)
  assert.equal(parseImagePlan('{"should_generate":true,"prompt":""}'), null)
  assert.equal(parseImagePlan('not json'), null)
})

test('accepts fenced JSON from providers that ignore the plain JSON request', () => {
  const plan = parseImagePlan('```json\n{"shouldGenerate":true,"prompt":"cat","aspectRatio":"wide"}\n```')
  assert.equal(plan.prompt, 'cat')
  assert.equal(plan.aspectRatio, 'square')
})

test('planner prompt explains persona reference ordering', () => {
  const prompt = buildImagePlannerPrompt({ personaPrompt: '保持温柔猫娘风格', referenceImageCount: 3 })
  assert.match(prompt, /参考图 1\/2\/3/)
  assert.match(prompt, /保持温柔猫娘风格/)
})

test('recognizes explicit image generation commands, including repeated input', () => {
  assert.equal(isExplicitImageGenerationRequest('生成一张蓝色猫娘图片'), true)
  assert.equal(isExplicitImageGenerationRequest('生成一张蓝色猫娘图生成一张蓝色猫娘图片'), true)
  assert.equal(isExplicitImageGenerationRequest('请帮我绘制一幅雨夜城市插画'), true)
  assert.equal(isExplicitImageGenerationRequest('来一张赛博朋克风格头像'), true)
  assert.equal(isExplicitImageGenerationRequest('发一张森林风景照片'), true)
  assert.equal(isExplicitImageGenerationRequest('Draw me a cat girl portrait'), true)
})

test('does not treat capability questions, tutorials, or negative requests as commands', () => {
  assert.equal(isExplicitImageGenerationRequest('你能生成图片吗'), false)
  assert.equal(isExplicitImageGenerationRequest('这个模型是否支持生成图片？'), false)
  assert.equal(isExplicitImageGenerationRequest('如何生成图片，有教程吗？'), false)
  assert.equal(isExplicitImageGenerationRequest('生成图片失败了怎么解决'), false)
  assert.equal(isExplicitImageGenerationRequest('不要生成图片，只回复文字'), false)
  assert.equal(isExplicitImageGenerationRequest('别给我画猫娘'), false)
  assert.equal(isExplicitImageGenerationRequest('我喜欢蓝色猫娘'), false)
  assert.equal(isExplicitImageGenerationRequest('给我分析这张图片'), false)
  assert.equal(isExplicitImageGenerationRequest('给我讲讲图片构图'), false)
})

test('keeps concrete question-shaped image requests actionable', () => {
  assert.equal(isExplicitImageGenerationRequest('你能帮我生成一张蓝色猫娘图片吗？'), true)
  assert.equal(isExplicitImageGenerationRequest('可以给我画一个头像吗'), true)
  assert.equal(isExplicitImageGenerationRequest('别担心，直接生成一张图片'), true)
})

test('broad planner pre-filter reacts to visual clues but respects opt-out', () => {
  assert.equal(shouldRunImageGenerationPlanner('聊聊这张图片的构图'), true)
  assert.equal(shouldRunImageGenerationPlanner('我想要一张竖版海报'), true)
  assert.equal(shouldRunImageGenerationPlanner('Please make an image of a cat'), true)
  assert.equal(shouldRunImageGenerationPlanner('不要生成图片，只聊天'), false)
  assert.equal(shouldRunImageGenerationPlanner('别画头像'), false)
  assert.equal(shouldRunImageGenerationPlanner('今天天气怎么样'), false)
})

test('builds a self-contained direct plan and preserves user and reference context', () => {
  const plan = buildDirectImagePlan({
    userText: '生成一张蓝色猫娘图片，霓虹夜景',
    personaPrompt: '银色短发，蓝色猫耳，温柔性格',
    referenceImageCount: 2,
  })

  assert.equal(plan.aspectRatio, 'square')
  assert.match(plan.prompt, /生成一张蓝色猫娘图片，霓虹夜景/)
  assert.match(plan.prompt, /银色短发，蓝色猫耳，温柔性格/)
  assert.match(plan.prompt, /2 张人格参考图/)
  assert.match(plan.prompt, /五官、发型、标志性服装/)
  assert.match(plan.caption, /Image 2\.0/)
})

test('infers direct-plan aspect ratio from requested use and orientation', () => {
  assert.equal(buildDirectImagePlan({ userText: '制作一个方形头像' }).aspectRatio, 'square')
  assert.equal(buildDirectImagePlan({ userText: '制作一张桌面壁纸' }).aspectRatio, 'landscape')
  assert.equal(buildDirectImagePlan({ userText: '绘制横版风景图' }).aspectRatio, 'landscape')
  assert.equal(buildDirectImagePlan({ userText: '设计竖版活动海报' }).aspectRatio, 'portrait')
  assert.equal(buildDirectImagePlan({ userText: '制作手机壁纸' }).aspectRatio, 'portrait')
})

test('direct plan does not claim reference images when none are provided', () => {
  const plan = buildDirectImagePlan({ userText: '画一幅星空插画', referenceImageCount: 0 })
  assert.match(plan.prompt, /本次没有人格参考图/)
  assert.doesNotMatch(plan.prompt, /系统会按顺序提供/)
})

test('direct plan strips chat metadata protocol from persona visual context', () => {
  const plan = buildDirectImagePlan({
    userText: '生成一张蓝色猫娘图片',
    personaPrompt: '蓝色短发猫娘，金色眼睛。每次回复后请在末尾提供：\n\n每次回复末尾必须追加且只追加一个 JSON 元数据 HTML 注释，格式：<!--{"emotion":"gentle","action":null,"generateImage":false,"imagePrompt":null}-->。',
  })
  assert.match(plan.prompt, /蓝色短发猫娘，金色眼睛/)
  assert.doesNotMatch(plan.prompt, /generateImage|imagePrompt|JSON 元数据/)
})

test('detects image-capability denial replies without flagging normal sentences', () => {
  assert.equal(isImageGenerationDenial('抱歉，我无法直接生成图片。'), true)
  assert.equal(isImageGenerationDenial('我没有生成图片的能力。我是一个专注于软件工程任务的编程助手（Claude Code）。'), true)
  assert.equal(isImageGenerationDenial('当前助手不具备图像生成能力'), true)
  assert.equal(isImageGenerationDenial('当前模型不支持绘制图像'), true)
  assert.equal(isImageGenerationDenial('图片生成功能暂不可用'), true)
  assert.equal(isImageGenerationDenial("I can't generate images here."), true)
  assert.equal(isImageGenerationDenial('Image generation is not available.'), true)
  assert.equal(isImageGenerationDenial('图片已经使用 Image 2.0 生成好了'), false)
  assert.equal(isImageGenerationDenial('不是不能生成图片，我已经在处理了'), false)
  assert.equal(isImageGenerationDenial('不能只生成图片，还要附上一句说明'), false)
  assert.equal(isImageGenerationDenial('我无法生成这段代码，但可以解释原理'), false)
  assert.equal(isImageGenerationDenial('我不能提供准确答案'), false)
})

test('post-image reply discards the complete denial instead of appending an Image 2.0 suffix', () => {
  const result = normalizePostImageReply('我没有生成图片的能力。我是一个专注于软件工程任务的编程助手（Claude Code），建议你改用其他工具。')
  assert.equal(result.cleanText, '图片已经生成并发送给你啦～这次是由 Image 2.0 配合完成的，看看是否符合你的想法吧。')
  assert.doesNotMatch(result.cleanText, /Claude Code|没有生成图片的能力|其他工具/)
  assert.equal(result.generateImage, false)
  assert.equal(result.imagePrompt, null)
})

test('persona reference validation allows up to three uploaded image URLs', () => {
  const urls = [
    'http://10.0.2.2:3000/api/v1/uploads/1.png',
    'http://10.0.2.2:3000/api/v1/uploads/2.png',
    'http://10.0.2.2:3000/api/v1/uploads/3.png',
  ]
  assert.deepEqual(validateReferenceImageUrls(urls), urls)
  assert.throws(
    () => validateReferenceImageUrls([...urls, 'https://example.com/4.png']),
    /最多上传 3 张/,
  )
  assert.throws(() => validateReferenceImageUrls(['file:///tmp/a.png']), /地址无效/)
  assert.throws(() => validateReferenceImageUrls(['https://example.com/a.png']), /本站上传服务/)
})

test('image generation retries transient gateway timeouts at low quality', () => {
  assert.deepEqual(imageQualityAttempts('high'), ['high', 'low'])
  assert.deepEqual(imageQualityAttempts('medium'), ['medium', 'low'])
  assert.deepEqual(imageQualityAttempts('low'), ['low'])
  assert.equal(isRetryableImageFailure(Object.assign(new Error('upstream timeout'), { status: 524 })), true)
  assert.equal(isRetryableImageFailure(Object.assign(new Error('invalid key'), { status: 401 })), false)
})
