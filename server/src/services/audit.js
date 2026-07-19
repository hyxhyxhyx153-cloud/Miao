import { pool } from '../db/client.js'

export async function recordAudit(req, action, targetType, targetId, details = {}) {
  await pool.query(
    `INSERT INTO audit_logs(admin_user_id,action,target_type,target_id,details,ip_address)
     VALUES($1,$2,$3,$4,$5,$6)`,
    [
      req.user?.sub || null,
      action,
      targetType || null,
      targetId == null ? null : String(targetId),
      JSON.stringify(details),
      req.ip || req.socket?.remoteAddress || null,
    ]
  )
}
