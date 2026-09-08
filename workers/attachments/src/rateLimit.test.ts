import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { handleAttachmentRequest, type WorkerEnv } from './index';
import type { RateLimiterBinding } from './auth';

const USER_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

function fakeBucket() {
  const objects = new Map<string, Uint8Array>();
  return {
    objects,
    async put(key: string, body: Uint8Array) {
      objects.set(key, body);
    },
    async get() {
      return null;
    },
    async head() {
      return null;
    },
    async delete(key: string) {
      objects.delete(key);
    },
  };
}

/** Records every key it is asked about, and refuses the ones it was told to refuse. */
function fakeLimiter(refuse: (key: string) => boolean = () => false) {
  const keys: string[] = [];
  const binding: RateLimiterBinding = {
    async limit({ key }) {
      keys.push(key);
      return { success: !refuse(key) };
    },
  };
  return { binding, keys };
}

let env: WorkerEnv;
let authCalls: number;

beforeEach(() => {
  authCalls = 0;
  env = {
    ATTACHMENTS_BUCKET: fakeBucket() as unknown as R2Bucket,
    SUPABASE_URL: 'https://project.supabase.co',
    SUPABASE_ANON_KEY: 'anon-key',
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string | URL) => {
      const url = String(input);
      if (url.includes('/auth/v1/user')) {
        authCalls += 1;
        return new Response(JSON.stringify({ id: USER_A }), { status: 200 });
      }
      return new Response(JSON.stringify({ allowed: false }), { status: 200 });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function get(headers: Record<string, string> = {}) {
  return handleAttachmentRequest(
    new Request('https://worker.example/v1/attachments/1/att1', {
      headers: { Authorization: `Bearer ${USER_A}`, ...headers },
    }),
    env,
  );
}

/**
 * Worker-level throttling is a deployment choice: bind a Cloudflare rate limiter and it applies,
 * leave it out and the Worker behaves exactly as before. Cloudflare counts at the edge, so unlike
 * an in-isolate counter the limit is not reset by the next request landing elsewhere.
 */
describe('attachment worker rate limiting', () => {
  it('does nothing when no limiter is bound', async () => {
    const response = await get({ 'CF-Connecting-IP': '203.0.113.7' });
    expect(response.status).toBe(404);
    expect(authCalls).toBe(1);
  });

  it('refuses an over-limit client before spending an auth request', async () => {
    const limiter = fakeLimiter((key) => key.startsWith('ip:'));
    env.ATTACHMENT_RATE_LIMITER = limiter.binding;

    const response = await get({ 'CF-Connecting-IP': '203.0.113.7' });

    expect(response.status).toBe(429);
    expect(response.headers.get('Retry-After')).toBe('60');
    expect(limiter.keys).toEqual(['ip:203.0.113.7']);
    expect(authCalls).toBe(0);
  });

  it('refuses an over-limit user after authenticating them', async () => {
    const limiter = fakeLimiter((key) => key.startsWith('user:'));
    env.ATTACHMENT_RATE_LIMITER = limiter.binding;

    const response = await get({ 'CF-Connecting-IP': '203.0.113.7' });

    expect(response.status).toBe(429);
    expect(limiter.keys).toEqual(['ip:203.0.113.7', `user:${USER_A}`]);
  });

  it('lets a request through when both keys are within their limits', async () => {
    const limiter = fakeLimiter();
    env.ATTACHMENT_RATE_LIMITER = limiter.binding;

    const response = await get({ 'CF-Connecting-IP': '203.0.113.7' });

    expect(response.status).toBe(404);
    expect(limiter.keys).toEqual(['ip:203.0.113.7', `user:${USER_A}`]);
  });

  it('skips the client limit when there is no client IP to key on', async () => {
    const limiter = fakeLimiter();
    env.ATTACHMENT_RATE_LIMITER = limiter.binding;

    await get();

    // Local `wrangler dev` sends no CF-Connecting-IP; keying every such request together would
    // throttle unrelated callers as one.
    expect(limiter.keys).toEqual([`user:${USER_A}`]);
  });

  it('fails open when the limiter itself errors', async () => {
    env.ATTACHMENT_RATE_LIMITER = {
      async limit() {
        throw new Error('rate limiter unavailable');
      },
    };

    const response = await get({ 'CF-Connecting-IP': '203.0.113.7' });

    // Throttling is a mitigation, not the authorization boundary: it must not take the API down.
    expect(response.status).toBe(404);
  });

  it('does not throttle a request that never reaches authentication', async () => {
    const limiter = fakeLimiter();
    env.ATTACHMENT_RATE_LIMITER = limiter.binding;

    const response = await handleAttachmentRequest(
      new Request('https://worker.example/invalid-path', {
        method: 'PATCH',
        headers: { 'CF-Connecting-IP': '203.0.113.7' },
      }),
      env,
    );

    expect(response.status).toBe(404);
    expect(limiter.keys).toEqual([]);
  });
});
