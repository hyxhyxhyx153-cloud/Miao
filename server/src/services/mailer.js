import nodemailer from 'nodemailer'

function smtpTransport() {
  if (!process.env.SMTP_HOST) return null
  return nodemailer.createTransport({
    host: process.env.SMTP_HOST,
    port: Number(process.env.SMTP_PORT || 587),
    secure: String(process.env.SMTP_SECURE || 'false') === 'true',
    auth: process.env.SMTP_USER ? {
      user: process.env.SMTP_USER,
      pass: process.env.SMTP_PASSWORD,
    } : undefined,
  })
}

export async function sendPasswordResetEmail(email, token) {
  const transport = smtpTransport()
  if (!transport) return false
  const resetBaseUrl = process.env.PASSWORD_RESET_URL || 'http://localhost:3000/reset-password'
  const resetUrl = `${resetBaseUrl}?token=${encodeURIComponent(token)}`
  await transport.sendMail({
    from: process.env.SMTP_FROM || process.env.SMTP_USER,
    to: email,
    subject: '喵 · 重置密码',
    text: `请在 30 分钟内打开以下链接重置密码：${resetUrl}`,
    html: `<p>请在 30 分钟内点击以下链接重置密码：</p><p><a href="${resetUrl}">${resetUrl}</a></p>`,
  })
  return true
}

export async function sendApiKeyUsageAlert(recipients, { provider, todayTokens, threshold }) {
  const transport = smtpTransport()
  const to = Array.isArray(recipients) ? recipients.filter(Boolean) : []
  if (!transport || !to.length) return false
  await transport.sendMail({
    from: process.env.SMTP_FROM || process.env.SMTP_USER,
    to,
    subject: `喵 · ${provider} API Key 用量告警`,
    text: `${provider} API Key 今日已使用 ${todayTokens} Token，达到告警阈值 ${threshold}。`,
    html: `<p><strong>${provider}</strong> API Key 今日已使用 <strong>${todayTokens}</strong> Token，达到告警阈值 ${threshold}。</p>`,
  })
  return true
}
