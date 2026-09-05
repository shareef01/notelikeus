import { describe, expect, it } from 'vitest';
import {
  isLocalSupabaseUrl,
  isBrowserSafeSupabaseKey,
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
  it('accepts a JWT-shaped anon key', () => {
    expect(isBrowserSafeSupabaseKey('eyJhbGciOiJIUzI1NiJ9.payload.signature')).toBe(true);
  });

  it('rejects secret and empty keys', () => {
    expect(isBrowserSafeSupabaseKey('sb_secret_x')).toBe(false);
    expect(isBrowserSafeSupabaseKey('sb_publishable_x')).toBe(false);
    expect(isBrowserSafeSupabaseKey('')).toBe(false);
  });
});
