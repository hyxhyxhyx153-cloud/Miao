import net from 'node:net'

function normalizeIp(value) {
  const ip = String(value || '').trim().split('%')[0]
  return ip.startsWith('::ffff:') && net.isIP(ip.slice(7)) === 4 ? ip.slice(7) : ip
}

function ipv4Value(ip) {
  return ip.split('.').reduce((value, part) => (value << 8n) | BigInt(Number(part)), 0n)
}

function ipv6Value(ip) {
  let source = ip.toLowerCase()
  const ipv4Match = source.match(/(?:^|:)(\d+\.\d+\.\d+\.\d+)$/)
  if (ipv4Match) {
    const value = ipv4Value(ipv4Match[1])
    source = source.slice(0, -ipv4Match[1].length) + `${(value >> 16n).toString(16)}:${(value & 0xffffn).toString(16)}`
  }
  const halves = source.split('::')
  if (halves.length > 2) throw new Error('Invalid IPv6 address')
  const left = halves[0] ? halves[0].split(':') : []
  const right = halves[1] ? halves[1].split(':') : []
  const missing = 8 - left.length - right.length
  if (missing < 0 || (halves.length === 1 && missing !== 0)) throw new Error('Invalid IPv6 address')
  const parts = [...left, ...Array(missing).fill('0'), ...right]
  return parts.reduce((value, part) => (value << 16n) | BigInt(`0x${part || '0'}`), 0n)
}

function parsedIp(value) {
  const ip = normalizeIp(value)
  const family = net.isIP(ip)
  if (!family) return null
  return {
    family,
    bits: family === 4 ? 32 : 128,
    value: family === 4 ? ipv4Value(ip) : ipv6Value(ip),
  }
}

export function ipMatchesRule(ip, rule) {
  const [network, prefixText] = String(rule || '').trim().split('/')
  const address = parsedIp(ip)
  const base = parsedIp(network)
  if (!address || !base || address.family !== base.family) return false
  if (prefixText == null) return address.value === base.value
  if (!/^\d+$/.test(prefixText)) return false
  const prefix = Number(prefixText)
  if (prefix < 0 || prefix > address.bits) return false
  if (prefix === 0) return true
  const shift = BigInt(address.bits - prefix)
  return (address.value >> shift) === (base.value >> shift)
}

export function isIpAllowed(ip, rules) {
  if (!Array.isArray(rules) || rules.length === 0) return true
  return rules.some(rule => ipMatchesRule(ip, rule))
}
