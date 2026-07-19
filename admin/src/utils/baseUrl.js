/**
 * Preserve the upstream address exactly as supplied by the provider.
 * Whitespace around copied values is ignored, but paths and trailing slashes
 * are significant and must never be inferred, appended, or removed here.
 */
export function normalizeBaseUrl(value) {
  return String(value ?? '').trim()
}
