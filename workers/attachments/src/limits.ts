/**
 * Server-side upload limits.
 *
 * The 10 MB cap the editors apply is a UX affordance, not a control: the Worker is reachable
 * directly with any Supabase access token, so anything not enforced here is not enforced at all.
 * Without a cap, `request.arrayBuffer()` buffers whatever the caller sends into Worker memory
 * before R2 ever sees it, and stores it against the R2 bill either way.
 */

/** Matches the 10 MB per-image cap the web and Kotlin editors apply. */
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

/** Attachments are images. Anything else is rejected rather than stored and served back. */
export const ALLOWED_ATTACHMENT_MIME_TYPES: readonly string[] = [
  'image/png',
  'image/jpeg',
  // Non-standard, but real: the backup format's allowlist accepts it on both Web and Kotlin
  // (`backupAttachments.ts`, `BackupAttachments.kt`). Omitting it here meant a backup carrying
  // `image/jpg` imported fine, stored a pending blob, and was then refused 415 on upload — the
  // note would import "successfully" with its image silently gone.
  'image/jpg',
  'image/webp',
  'image/gif',
  'image/heic',
  'image/heif',
  'image/avif',
];

/** Strips `; charset=…` and lowercases, so `IMAGE/PNG; charset=x` still matches. */
export function normalizeMimeType(headerValue: string | null): string {
  return (headerValue ?? '').split(';')[0]!.trim().toLowerCase();
}

export function isAllowedAttachmentMimeType(headerValue: string | null): boolean {
  return ALLOWED_ATTACHMENT_MIME_TYPES.includes(normalizeMimeType(headerValue));
}

/**
 * Declared size from `Content-Length`, or null when absent or unparseable (chunked uploads).
 * A declared size lets an oversized body be refused before it is read; an undeclared one is
 * caught by {@link readBodyWithinLimit} while streaming.
 */
export function declaredContentLength(headerValue: string | null): number | null {
  if (headerValue == null) return null;
  const parsed = Number(headerValue.trim());
  if (!Number.isSafeInteger(parsed) || parsed < 0) return null;
  return parsed;
}

export class AttachmentTooLargeError extends Error {
  constructor(readonly limitBytes: number) {
    super(`Attachment exceeds ${limitBytes} bytes`);
    this.name = 'AttachmentTooLargeError';
  }
}

/**
 * Reads the body, aborting as soon as more than [limitBytes] have arrived.
 *
 * Streaming rather than `arrayBuffer()` is the point: a caller that lies in `Content-Length`, or
 * omits it entirely, must not be able to push an unbounded body into Worker memory first.
 */
export async function readBodyWithinLimit(
  body: ReadableStream<Uint8Array> | null,
  limitBytes: number,
): Promise<Uint8Array> {
  if (!body) return new Uint8Array(0);

  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > limitBytes) {
        await reader.cancel().catch(() => {});
        throw new AttachmentTooLargeError(limitBytes);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return merged;
}
