import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveAuthenticatedUserId, UpstreamServiceError } from './auth';

const env = {
  SUPABASE_URL: 'https://supabase.example.com',
  SUPABASE_ANON_KEY: 'test-anon-key',
};

describe('F08: Supabase Auth Response Runtime Validation', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function makeRequest(token = 'valid-token'): Request {
    return new Request('https://worker.example.com/owners/u/notes/1/att-1', {
      headers: { Authorization: `Bearer ${token}` },
    });
  }

  function mockAuthFetch(status: number, body: string): void {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(body, {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }

  // Case 1 ÔÇö valid user
  it('Case 1: resolves trimmed userId for valid user payload', async () => {
    mockAuthFetch(200, JSON.stringify({ id: ' user-123 ' }));
    const userId = await resolveAuthenticatedUserId(makeRequest(), env);
    expect(userId).toBe('user-123');
  });

  // Case 2 ÔÇö invalid JSON
  it('Case 2: throws controlled UpstreamServiceError on invalid JSON', async () => {
    mockAuthFetch(200, '<html>gateway error</html>');
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.toThrow(
      UpstreamServiceError,
    );
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.not.toThrow(SyntaxError);
  });

  // Case 3 ÔÇö empty body
  it('Case 3: throws controlled UpstreamServiceError on empty body', async () => {
    mockAuthFetch(200, '');
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.toThrow(
      UpstreamServiceError,
    );
  });

  // Case 4 ÔÇö null
  it('Case 4: throws controlled UpstreamServiceError on null payload', async () => {
    mockAuthFetch(200, 'null');
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.toThrow(
      UpstreamServiceError,
    );
  });

  // Case 5 ÔÇö array
  it('Case 5: throws controlled UpstreamServiceError on array payload', async () => {
    mockAuthFetch(200, '[]');
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.toThrow(
      UpstreamServiceError,
    );
  });

  // Case 6 ÔÇö empty object
  it('Case 6: throws controlled UpstreamServiceError on empty object (missing id)', async () => {
    mockAuthFetch(200, '{}');
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.toThrow(
      UpstreamServiceError,
    );
  });

  // Case 7 ÔÇö numeric id
  it('Case 7: throws controlled UpstreamServiceError on numeric id', async () => {
    mockAuthFetch(200, JSON.stringify({ id: 123 }));
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.toThrow(
      UpstreamServiceError,
    );
  });

  // Case 8 ÔÇö blank string id
  it('Case 8: throws controlled UpstreamServiceError on blank string id', async () => {
    mockAuthFetch(200, JSON.stringify({ id: '   ' }));
    await expect(resolveAuthenticatedUserId(makeRequest(), env)).rejects.toThrow(
      UpstreamServiceError,
    );
  });

  // Upstream 401/403 differentiation
  it('returns null on upstream 401/403 (unauthenticated)', async () => {
    mockAuthFetch(401, JSON.stringify({ message: 'Invalid token' }));
    const result = await resolveAuthenticatedUserId(makeRequest(), env);
    expect(result).toBeNull();
  });

  // Upstream 5xx differentiation
  it('throws 502 UpstreamServiceError on upstream 5xx', async () => {
    mockAuthFetch(500, 'Internal Server Error');
    let error: UpstreamServiceError | null = null;
    try {
      await resolveAuthenticatedUserId(makeRequest(), env);
    } catch (e) {
      error = e as UpstreamServiceError;
    }
    expect(error).toBeInstanceOf(UpstreamServiceError);
    expect(error?.status).toBe(502);
  });
});
