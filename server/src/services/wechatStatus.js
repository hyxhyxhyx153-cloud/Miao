function ageSeconds(value, now) {
  if (!value) return null
  const timestamp = new Date(value).getTime()
  if (!Number.isFinite(timestamp)) return null
  return Math.max(0, Math.floor((now - timestamp) / 1000))
}

export function buildWechatBindingStatus(row, {
  workerActive = false,
  now = Date.now(),
  staleAfterSeconds = 75,
} = {}) {
  const heartbeatAgeSeconds = ageSeconds(row.last_heartbeat_at, now)
  const lastMessageAgeSeconds = ageSeconds(row.last_message_at, now)
  const lastDeliveryAgeSeconds = ageSeconds(row.last_delivery_at, now)

  let connectionStatus
  if (row.worker_status === 'expired') {
    connectionStatus = 'expired'
  } else if (!workerActive) {
    connectionStatus = row.worker_status === 'stopped' ? 'stopped' : 'offline'
  } else if (row.worker_status === 'starting') {
    connectionStatus = 'starting'
  } else if (row.worker_status === 'reconnecting' || row.worker_status === 'error') {
    connectionStatus = 'reconnecting'
  } else if (heartbeatAgeSeconds == null) {
    connectionStatus = 'starting'
  } else if (heartbeatAgeSeconds > staleAfterSeconds) {
    connectionStatus = 'reconnecting'
  } else {
    connectionStatus = 'running'
  }

  return {
    ...row,
    connection_status: connectionStatus,
    heartbeat_age_seconds: heartbeatAgeSeconds,
    last_message_age_seconds: lastMessageAgeSeconds,
    last_delivery_age_seconds: lastDeliveryAgeSeconds,
  }
}
