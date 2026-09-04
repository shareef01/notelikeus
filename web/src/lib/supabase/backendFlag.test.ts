import { describe, expect, it } from 'vitest';
import {
  isLocalSupabaseUrl,
  isBrowserSafeSupabaseKey,
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

describe('isBrowserSafeSupabaseKey', () => {
  it('accepts the public anon JWT', () => {
    expect(isBrowserSafeSupabaseKey('eyJhbGciOiJIUzI1NiJ9.payload.signature')).toBe(true);
  });

  it('rejects secret API keys and anything else that is not a JWT', () => {
    expect(isBrowserSafeSupabaseKey('sb_secret_not_a_real_key')).toBe(false);
    expect(isBrowserSafeSupabaseKey('SB_SECRET_not_a_real_key')).toBe(false);
    expect(isBrowserSafeSupabaseKey('sb_publishable_something')).toBe(false);
    expect(isBrowserSafeSupabaseKey('')).toBe(false);
    expect(isBrowserSafeSupabaseKey('   ')).toBe(false);
    expect(isBrowserSafeSupabaseKey('service-role-please')).toBe(false);
  });
});

/**
 * Migration safety rails: these exist to fail loudly if a staging change ever makes a production
 * build reach Supabase. Firebase stays the production backend until an owner-authorised cutover.
 */
describe('production isolation', () => {
  const hosted = 'https://abcd.supabase.co';
  const anonKey = 'eyJhbGciOiJIUzI1NiJ9.payload.signature';

  it('firebaseProductionHost_neverSelectsSupabaseFromStagingFlag', () => {
    for (const hostname of ['notelike.web.app', 'notelike.firebaseapp.com', 'www.notelike.web.app']) {
      expect(
        resolveSupabaseBackendEnabled({
          isProd: true,
          isE2e: false,
          remoteBackend: 'supabase',
          allowStaging: 'true',
          supabaseUrl: hosted,
          anonKey,
          hostname,
        }),
        hostname,
      ).toBe(false);
    }
  });

  it('pagesDev_withStagingFlag_selectsSupabase', () => {
    expect(
      resolveSupabaseBackendEnabled({
        isProd: true,
        isE2e: false,
        remoteBackend: 'supabase',
        allowStaging: 'true',
        supabaseUrl: hosted,
        anonKey,
        hostname: 'notelikeus-dev.pages.dev',
      }),
    ).toBe(true);
  });

  it('productionWithoutExplicitAllow_selectsFirebase', () => {
    expect(
      resolveSupabaseBackendEnabled({
        isProd: true,
        isE2e: false,
        remoteBackend: 'supabase',
        supabaseUrl: hosted,
        anonKey,
        hostname: 'notelike.web.app',
      }),
    ).toBe(false);
  });

  it('lookalikeStagingHostnames_doNotSelectSupabase', () => {
    for (const hostname of [
      'pages.dev.attacker.example',
      'notpages.dev',
      'example.pages.dev.attacker.example',
      'pages-dev.attacker.example',
      'evil.com/notelikeus-dev.pages.dev',
      '',
    ]) {
      expect(
        resolveSupabaseBackendEnabled({
          isProd: true,
          isE2e: false,
          remoteBackend: 'supabase',
          allowStaging: 'true',
          supabaseUrl: hosted,
          anonKey,
          hostname,
        }),
        hostname,
      ).toBe(false);
    }
  });

  it('secretSupabaseKey_isRejectedFromBrowserConfig', () => {
    for (const isProd of [true, false]) {
      expect(
        resolveSupabaseBackendEnabled({
          isProd,
          isE2e: false,
          remoteBackend: 'supabase',
          allowProduction: 'true',
          supabaseUrl: hosted,
          anonKey: 'sb_secret_not_a_real_key',
          hostname: 'notelikeus-dev.pages.dev',
        }),
      ).toBe(false);
    }
  });

  it('missingRemoteBackend_selectsFirebase', () => {
    expect(
      resolveSupabaseBackendEnabled({
        isProd: true,
        isE2e: false,
        supabaseUrl: hosted,
        anonKey,
        hostname: 'notelikeus-dev.pages.dev',
      }),
    ).toBe(false);
  });

  it('malformedSupabaseUrl_failsSafeInProduction', () => {
    for (const supabaseUrl of ['', 'not a url', 'http://localhost:54321']) {
      expect(
        resolveSupabaseBackendEnabled({
          isProd: true,
          isE2e: false,
          remoteBackend: 'supabase',
          allowProduction: 'true',
          supabaseUrl,
          anonKey,
          hostname: 'notelike.web.app',
        }),
        supabaseUrl,
      ).toBe(false);
    }
  });
});
