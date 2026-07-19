import http from 'node:http'
import https from 'node:https'
import crypto from 'node:crypto'

const CLAWBOT_BASE_URL = process.env.CLAWBOT_BASE_URL || 'https://ilinkai.weixin.qq.com'

function requestJson(path, {
  query = {}, headers = {}, timeoutMs = 15_000, method = 'GET', body, baseUrl = CLAWBOT_BASE_URL, signal,
} = {}) {
  return new Promise((resolve, reject) => {
    const url = new URL(path, baseUrl)
    for (const [name, value] of Object.entries(query)) {
      url.searchParams.set(name, value)
    }

    const client = url.protocol === 'http:' ? http : https
    const payload = body == null ? null : JSON.stringify(body)
    let settled = false
    let req
    const settle = (callback, value) => {
      if (settled) return
      settled = true
      clearTimeout(wallClockTimer)
      callback(value)
    }
    const fail = error => settle(reject, error)
    const succeed = value => settle(resolve, value)
    const timeoutError = new Error('ClawBot request timed out')
    const wallClockTimer = setTimeout(() => {
      req?.destroy(timeoutError)
      fail(timeoutError)
    }, timeoutMs)
    wallClockTimer.unref?.()

    req = client.request(url, {
      method,
      signal,
      headers: {
        Accept: 'application/json',
        ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } : {}),
        ...headers,
      },
    }, (res) => {
      let raw = ''
      let ended = false
      res.setEncoding('utf8')
      res.on('data', chunk => { raw += chunk })
      res.once('aborted', () => fail(new Error('ClawBot response was interrupted')))
      res.once('error', fail)
      res.once('close', () => {
        if (!ended) fail(new Error('ClawBot response closed before completion'))
      })
      res.on('end', () => {
        ended = true
        let data
        try {
          data = JSON.parse(raw)
        } catch {
          fail(new Error(`ClawBot returned an invalid response (${res.statusCode})`))
          return
        }

        if ((res.statusCode || 500) >= 400) {
          const error = new Error(data.errmsg || data.error || `ClawBot request failed (${res.statusCode})`)
          error.statusCode = res.statusCode
          error.retryable = res.statusCode >= 500 || [408, 409, 425, 429].includes(res.statusCode)
          fail(error)
          return
        }
        succeed(data)
      })
    })

    req.on('error', fail)
    if (payload) req.write(payload)
    req.end()
  })
}

function throwForClawbotCode(data, fallback) {
  const errorCode = [data.ret, data.errcode].find(code => code != null && code !== 0)
  if (errorCode == null) return
  const error = new Error(data.errmsg || `${fallback} (${errorCode})`)
  error.clawbotCode = errorCode
  error.retryable = errorCode !== -14
  throw error
}

function authenticatedHeaders(botToken) {
  const randomUin = crypto.randomInt(0, 2 ** 32)
  return {
    AuthorizationType: 'ilink_bot_token',
    Authorization: `Bearer ${botToken}`,
    'X-WECHAT-UIN': Buffer.from(String(randomUin)).toString('base64'),
    'iLink-App-Id': 'bot',
    'iLink-App-ClientVersion': '131072',
  }
}

function baseInfo() {
  return { channel_version: '2.0.0', bot_agent: 'Miao/1.0.0' }
}

export async function getClawbotUpdates({ baseUrl, botToken, getUpdatesBuf = '', signal }) {
  const data = await requestJson('/ilink/bot/getupdates', {
    baseUrl,
    method: 'POST',
    headers: authenticatedHeaders(botToken),
    body: {
      get_updates_buf: getUpdatesBuf,
      base_info: baseInfo(),
    },
    timeoutMs: 45_000,
    signal,
  })
  const errorCode = [data.ret, data.errcode]
    .find(code => code != null && code !== 0 && code !== -14)
  if (errorCode != null) {
    throw new Error(data.errmsg || `ClawBot getupdates failed (${errorCode})`)
  }
  return data
}

export async function sendClawbotMessage({
  baseUrl,
  botToken,
  toUserId,
  contextToken,
  clientId,
  text,
  signal,
}) {
  const data = await requestJson('/ilink/bot/sendmessage', {
    baseUrl,
    method: 'POST',
    headers: authenticatedHeaders(botToken),
    body: {
      msg: {
        from_user_id: '',
        to_user_id: toUserId,
        client_id: clientId,
        context_token: contextToken,
        message_type: 2,
        message_state: 2,
        item_list: [{ type: 1, text_item: { text } }],
      },
      base_info: baseInfo(),
    },
    timeoutMs: 20_000,
    signal,
  })
  throwForClawbotCode(data, 'ClawBot sendmessage failed')
  return data
}

export async function notifyClawbotStart({ baseUrl, botToken }) {
  const data = await requestJson('/ilink/bot/msg/notifystart', {
    baseUrl,
    method: 'POST',
    headers: authenticatedHeaders(botToken),
    body: { base_info: baseInfo() },
    timeoutMs: 10_000,
  })
  throwForClawbotCode(data, 'ClawBot notify start failed')
  return data
}

// ClawBot calls channel teardown "notifystop". Exposing it as a reset
// operation keeps both the user channel_reset route and admin unbind flow
// aligned with the upstream protocol.
export async function resetClawbotChannel({ baseUrl, botToken }) {
  const data = await requestJson('/ilink/bot/msg/notifystop', {
    baseUrl,
    method: 'POST',
    headers: authenticatedHeaders(botToken),
    body: { base_info: baseInfo() },
    timeoutMs: 10_000,
  })
  throwForClawbotCode(data, 'ClawBot channel reset failed')
  return data
}

export async function getClawbotQrCode() {
  const data = await requestJson('/ilink/bot/get_bot_qrcode', {
    query: { bot_type: '3' },
    timeoutMs: 15_000,
  })

  if ((data.ret != null && data.ret !== 0) || !data.qrcode || !data.qrcode_img_content) {
    throw new Error(data.errmsg || 'ClawBot did not return a valid QR code')
  }

  return {
    qrcode: data.qrcode,
    qrcode_url: data.qrcode_img_content,
  }
}

export async function getClawbotQrStatus(qrcode) {
  const data = await requestJson('/ilink/bot/get_qrcode_status', {
    query: { qrcode },
    headers: { 'iLink-App-ClientVersion': '1' },
    timeoutMs: 40_000,
  })

  if (!data.status) throw new Error(data.errmsg || 'ClawBot did not return a QR status')

  return {
    status: data.status,
    credentials: data.status === 'confirmed' ? {
      bot_token: data.bot_token,
      ilink_bot_id: data.ilink_bot_id,
      ilink_user_id: data.ilink_user_id,
      baseurl: data.baseurl || CLAWBOT_BASE_URL,
    } : null,
  }
}
