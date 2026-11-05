import {
  attachmentDeferredWorkerPath,
  attachmentWorkerPath,
  buildAttachmentObjectKey,
} from '@/lib/attachments/attachmentObjectKey';
import type {
  AttachmentBlobStore,
  AttachmentBlobUploadResult,
} from '@/lib/attachments/attachmentBlobStore';
import { loadAttachmentsWorkerUrl } from '@/lib/attachments/attachmentConfig';
import { getSupabaseClient } from '@/lib/supabase/client';

/** Waits before each retry of a request the Worker throttled, when it sends no usable Retry-After. */
const RATE_LIMIT_WAITS_MS = [15_000, 45_000];
const MAX_RATE_LIMIT_WAIT_MS = 60_000;

function retryAfterMs(response: Response, fallbackMs: number): number {
  const seconds = Number(response.headers.get('Retry-After'));
  if (!Number.isFinite(seconds) || seconds <= 0) return fallbackMs;
  return Math.min(seconds * 1000, MAX_RATE_LIMIT_WAIT_MS);
}

/**
 * Sends a Worker request, and when the Worker answers 429 (too many requests), waits and sends it
 * again, at most twice. The Worker counts requests per user over a 60-second window, so a new device
 * loading hundreds of images would otherwise fail every load past the limit until a later sync.
 */
export async function fetchWithRateLimitRetry(
  send: () => Promise<Response>,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
): Promise<Response> {
  for (const fallbackMs of RATE_LIMIT_WAITS_MS) {
    const response = await send();
    if (response.status !== 429) return response;
    await sleep(retryAfterMs(response, fallbackMs));
  }
  return send();
}

export function createR2AttachmentBlobStore(
  workerBaseUrl: string = loadAttachmentsWorkerUrl(),
): AttachmentBlobStore {
  const base = workerBaseUrl.replace(/\/$/, '');

  async function accessToken(expectedOwnerId?: string): Promise<string> {
    const { data, error } = await getSupabaseClient().auth.getSession();
    const token = data.session?.access_token?.trim();
    if (error || !token || (expectedOwnerId && data.session?.user.id !== expectedOwnerId)) {
      throw new Error('missing Supabase session for attachment upload');
    }
    return token;
  }

  return {
    async upload(noteId, attachmentId, blob, mimeType, expectedOwnerId): Promise<AttachmentBlobUploadResult> {
      const { data, error } = await getSupabaseClient().auth.getSession();
      const token = data.session?.access_token?.trim();
      const userId = data.session?.user.id;
      if (error || !token || !userId || (expectedOwnerId && userId !== expectedOwnerId)) {
        throw new Error('Account changed during attachment upload');
      }
      const objectKey = buildAttachmentObjectKey(userId, noteId, attachmentId);
      // Deferred protocol, with no fallback to v1: v1 would commit the row before any note names it.
      const response = await fetchWithRateLimitRetry(() =>
        fetch(`${base}${attachmentDeferredWorkerPath(noteId, attachmentId)}`, {
          method: 'PUT',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': mimeType,
          },
          body: blob,
        }),
      );
      if (!response.ok) {
        throw new Error(`attachment upload failed (${response.status})`);
      }
      const payload = (await response.json()) as {
        objectKey?: string;
        sizeBytes?: number;
        mimeType?: string;
      };
      return {
        objectKey: payload.objectKey ?? objectKey,
        sizeBytes: payload.sizeBytes ?? blob.size,
        mimeType: payload.mimeType ?? mimeType,
      };
    },

    async download(noteId, attachmentId, expectedOwnerId): Promise<Blob> {
      const token = await accessToken(expectedOwnerId);
      const response = await fetchWithRateLimitRetry(() =>
        fetch(`${base}${attachmentWorkerPath(noteId, attachmentId)}`, {
          headers: { Authorization: `Bearer ${token}` },
        }),
      );
      if (!response.ok) {
        throw new Error(`attachment download failed (${response.status})`);
      }
      return response.blob();
    },

    async delete(noteId, attachmentId, expectedOwnerId): Promise<void> {
      const token = await accessToken(expectedOwnerId);
      const response = await fetchWithRateLimitRetry(() =>
        fetch(`${base}${attachmentWorkerPath(noteId, attachmentId)}`, {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` },
        }),
      );
      if (!response.ok) {
        throw new Error(`attachment delete failed (${response.status})`);
      }
    },
  };
}
