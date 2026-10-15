import { describe, expect, it } from 'vitest';
import { fetchWithRateLimitRetry } from '@/lib/attachments/r2AttachmentBlobStore';

function respond(status: number, headers: Record<string, string> = {}): Response {
  return new Response(status === 200 ? 'ok' : null, { status, headers });
}

describe('fetchWithRateLimitRetry', () => {
  it('waits the Retry-After the Worker sends, then retries until it succeeds', async () => {
    const statuses = [429, 429, 200];
    const waits: number[] = [];
    const response = await fetchWithRateLimitRetry(
      async () => respond(statuses.shift()!, { 'Retry-After': '60' }),
      async (ms) => {
        waits.push(ms);
      },
    );
    expect(response.status).toBe(200);
    expect(waits).toEqual([60_000, 60_000]);
  });

  it('falls back to its own waits when Retry-After is missing, and caps a long one', async () => {
    const replies = [respond(429), respond(429, { 'Retry-After': '3600' }), respond(200)];
    const waits: number[] = [];
    await fetchWithRateLimitRetry(
      async () => replies.shift()!,
      async (ms) => {
        waits.push(ms);
      },
    );
    expect(waits).toEqual([15_000, 60_000]);
  });

  it('returns the third 429 rather than retrying forever', async () => {
    let calls = 0;
    const response = await fetchWithRateLimitRetry(
      async () => {
        calls++;
        return respond(429);
      },
      async () => {},
    );
    expect(response.status).toBe(429);
    expect(calls).toBe(3);
  });

  it('does not retry any other status', async () => {
    let calls = 0;
    const response = await fetchWithRateLimitRetry(
      async () => {
        calls++;
        return respond(403);
      },
      async () => {
        throw new Error('should not wait');
      },
    );
    expect(response.status).toBe(403);
    expect(calls).toBe(1);
  });
});
