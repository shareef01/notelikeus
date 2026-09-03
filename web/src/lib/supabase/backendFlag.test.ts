import { describe, expect, it } from 'vitest';
import {
  isLocalSupabaseUrl,
  isPagesDevHost,
  resolveSupabaseBackendEnabled,
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

describe('isPagesDevHost', () => {
  it('matches Pages hosts only', () => {
    expect(isPagesDevHost('notelikeus-dev.pages.dev')).toBe(true);
    expect(isPagesDevHost('abc.notelikeus-dev.pages.dev')).toBe(true);
    expect(isPagesDevHost('notelike.web.app')).toBe(false);
    expect(isPagesDevHost('pages.dev.evil.example')).toBe(false);
  });
});

describe('resolveSupabaseBackendEnabled', () => {
  const hosted = 'https://abcd.supabase.co';

  it('stays off unless remote backend is supabase', () => {
    expect(
      resolveSupabaseBackendEnabled({
        isProd: false,
        isE2e: false,
        remoteBackend: 'firebase',
        supabaseUrl: hosted,
      }),
    ).toBe(false);
  });

  it('allows supabase in development without a production override', () => {
    expect(
      resolveSupabaseBackendEnabled({
        isProd: false,
        isE2e: false,
        remoteBackend: 'supabase',
        supabaseUrl: 'http://127.0.0.1:54321',
      }),
    ).toBe(true);
  });

  it('blocks production unless the explicit cutover flag and hosted URL are set', () => {
    expect(
      resolveSupabaseBackendEnabled({
        isProd: true,
        isE2e: false,
        remoteBackend: 'supabase',
        supabaseUrl: hosted,
      }),
    ).toBe(false);
    expect(
      resolveSupabaseBackendEnabled({
        isProd: true,
        isE2e: false,
        remoteBackend: 'supabase',
        allowProduction: 'true',
        supabaseUrl: 'http://127.0.0.1:54321',
      }),
    ).toBe(false);
    expect(
      resolveSupabaseBackendEnabled({
        isProd: true,
        isE2e: false,
        remoteBackend: 'supabase',
        allowProduction: 'true',
        supabaseUrl: hosted,
      }),
    ).toBe(true);
  });

  it('allows e2e production builds without the cutover flag', () => {
    expect(
      resolveSupabaseBackendEnabled({
        isProd: true,
        isE2e: true,
        remoteBackend: 'supabase',
        supabaseUrl: 'http://127.0.0.1:54321',
      }),
    ).toBe(true);
  });

  it('allows a Pages staging build only on pages.dev hosts', () => {
    const staging = {
      isProd: true,
      isE2e: false,
      remoteBackend: 'supabase',
      allowStaging: 'true',
      supabaseUrl: hosted,
    };
    expect(
      resolveSupabaseBackendEnabled({
        ...staging,
        hostname: 'notelikeus-dev.pages.dev',
      }),
    ).toBe(true);
    expect(
      resolveSupabaseBackendEnabled({
        ...staging,
        hostname: '7c3e5ca7.notelikeus-dev.pages.dev',
      }),
    ).toBe(true);
    expect(
      resolveSupabaseBackendEnabled({
        ...staging,
        hostname: 'notelike.web.app',
      }),
    ).toBe(false);
    expect(resolveSupabaseBackendEnabled(staging)).toBe(false);
  });
});
