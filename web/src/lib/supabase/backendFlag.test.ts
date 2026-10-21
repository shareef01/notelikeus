import { describe, expect, it } from 'vitest';
import {
  isLocalSupabaseUrl,
  isBrowserSafeSupabaseKey,
  supabaseClientKeyKind,
  isSupabaseConfigured,
} from '@/lib/supabase/backendFlag';

describe('isLocalSupabaseUrl', () => {
  it('treats localhost variants as local', () => {
    expect(isLocalSupabaseUrl('http://127.0.0.1:54321')).toBe(true);
    expect(isLocalSupabaseUrl('http://localhost:54321')).toBe(true);
    expect(isLocalSupabaseUrl('http://app.local')).toBe(true);
    expect(isLocalSupabaseUrl('not a url')).toBe(true);
    expect(isLocalSupabaseUrl('')).toBe(true);
  });

  it('accepts hosted project URLs', () => {
    expect(isLocalSupabaseUrl('https://abcd.supabase.co')).toBe(false);
  });
});

describe('isSupabaseConfigured', () => {
  const hosted = 'https://abcd.supabase.co';
  const anon = 'eyJhbGciOiJIUzI1NiJ9.payload.signature';

  it('allows local Supabase in development', () => {
    expect(
      isSupabaseConfigured({
        isProd: false,
        isE2e: false,
        supabaseUrl: 'http://127.0.0.1:54321',
        anonKey: anon,
      }),
    ).toBe(true);
  });

  it('allows hosted Supabase in production', () => {
    expect(
      isSupabaseConfigured({
        isProd: true,
        isE2e: false,
        supabaseUrl: hosted,
        anonKey: anon,
      }),
    ).toBe(true);
  });

  it('blocks a production build pointed at localhost', () => {
    expect(
      isSupabaseConfigured({
        isProd: true,
        isE2e: false,
        supabaseUrl: 'http://127.0.0.1:54321',
        anonKey: anon,
      }),
    ).toBe(false);
  });

  it('allows localhost in an e2e production build', () => {
    expect(
      isSupabaseConfigured({
        isProd: true,
        isE2e: true,
        supabaseUrl: 'http://127.0.0.1:54321',
        anonKey: anon,
      }),
    ).toBe(true);
  });

  it('rejects a secret API key', () => {
    expect(
      isSupabaseConfigured({
        isProd: false,
        isE2e: false,
        supabaseUrl: hosted,
        anonKey: 'sb_secret_not_a_real_key',
      }),
    ).toBe(false);
  });

  it('rejects a missing anon key', () => {
    expect(
      isSupabaseConfigured({
        isProd: false,
        isE2e: false,
        supabaseUrl: hosted,
        anonKey: '',
      }),
    ).toBe(false);
  });
});

describe('isBrowserSafeSupabaseKey', () => {
  const jwt = (claims: object) =>
    `eyJhbGciOiJIUzI1NiJ9.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.signature`;

  it('accepts a JWT-shaped anon key', () => {
    expect(isBrowserSafeSupabaseKey('eyJhbGciOiJIUzI1NiJ9.payload.signature')).toBe(true);
    expect(isBrowserSafeSupabaseKey(jwt({ role: 'anon' }))).toBe(true);
  });

  it('accepts a publishable key', () => {
    expect(isBrowserSafeSupabaseKey('sb_publishable_abc123')).toBe(true);
    expect(isBrowserSafeSupabaseKey('  sb_publishable_abc123  ')).toBe(true);
  });

  it('rejects secret, service_role and empty keys', () => {
    expect(isBrowserSafeSupabaseKey('sb_secret_x')).toBe(false);
    expect(isBrowserSafeSupabaseKey('SB_SECRET_x')).toBe(false);
    expect(isBrowserSafeSupabaseKey('sb_publishable_')).toBe(false);
    expect(isBrowserSafeSupabaseKey('sb_publishablex')).toBe(false);
    expect(isBrowserSafeSupabaseKey(jwt({ role: 'service_role' }))).toBe(false);
    expect(isBrowserSafeSupabaseKey('not-a-key')).toBe(false);
    expect(isBrowserSafeSupabaseKey('')).toBe(false);
  });
});

describe('supabaseClientKeyKind', () => {
  it('names the kind of each client key', () => {
    expect(supabaseClientKeyKind('sb_publishable_abc123')).toBe('publishable');
    expect(supabaseClientKeyKind('eyJhbGciOiJIUzI1NiJ9.payload.signature')).toBe('legacy-anon-jwt');
    expect(supabaseClientKeyKind('sb_secret_x')).toBeNull();
  });
});

describe('isSupabaseConfigured with a publishable key', () => {
  it('enables a hosted production build', () => {
    expect(
      isSupabaseConfigured({
        isProd: true,
        isE2e: false,
        supabaseUrl: 'https://abc.supabase.co',
        anonKey: 'sb_publishable_abc123',
      }),
    ).toBe(true);
  });
});
