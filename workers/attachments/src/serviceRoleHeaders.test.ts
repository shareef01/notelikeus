import { describe, expect, it } from 'vitest';
import { serviceRoleHeaders } from './sweep';
import type { WorkerEnv } from './auth';

const env = (key: string | undefined) => ({ SUPABASE_SERVICE_ROLE_KEY: key }) as unknown as WorkerEnv;

describe('serviceRoleHeaders', () => {
  it('sends a secret API key on apikey only, never as a bearer token', () => {
    // Supabase Auth verifies a bearer token as a JWT, so an sb_secret_ key there made every admin
    // delete fail: account deletions started but never removed the user.
    const headers = serviceRoleHeaders(env('sb_secret_abc123')) as Record<string, string>;
    expect(headers.apikey).toBe('sb_secret_abc123');
    expect(headers).not.toHaveProperty('Authorization');
  });

  it('keeps the legacy service_role JWT on both headers', () => {
    const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.sig';
    const headers = serviceRoleHeaders(env(jwt)) as Record<string, string>;
    expect(headers.apikey).toBe(jwt);
    expect(headers.Authorization).toBe(`Bearer ${jwt}`);
  });

  it('returns null without a key', () => {
    expect(serviceRoleHeaders(env(undefined))).toBeNull();
    expect(serviceRoleHeaders(env('  '))).toBeNull();
  });
});
