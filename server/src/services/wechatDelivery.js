export async function deliverWechatBatch(messages, {
  isStopped,
  respond,
  persistFailure,
}) {
  for (const message of messages || []) {
    if (isStopped()) return false
    try {
      await respond(message)
    } catch (error) {
      if (isStopped()) return false
      if (error?.fatalWechatBinding) throw error
      // persistFailure must resolve only after an error reply or an already
      // persisted normal reply has been accepted by WeChat. If delivery still
      // fails it throws, preventing the caller from advancing the cursor.
      await persistFailure(message, error)
    }
  }
  return !isStopped()
}
