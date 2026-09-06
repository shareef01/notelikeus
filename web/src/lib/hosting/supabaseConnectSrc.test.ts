import { describe, expect, it } from 'vitest';
import {
  applySupabaseConnectSrc,
  requireSupabaseConnectSrcTokens,
  supabaseConnectSrcTokens,
} from '@/lib/hosting/supabaseConnectSrc';

const BASE_HEADERS = `/*
  Content-Security-Policy: default-src 'self'; connect-src 'self' https://accounts.google.com
`;

describe('supabaseConnectSrcTokens', () => {
  it('pins a hosted project https and wss origin', () => {
    expect(supabaseConnectSrcTokens('https://abcd.supabase.co')).toEqual({
      https: 'https://abcd.supabase.co',
      wss: 'wss://abcd.supabase.co',
    });
  });

  it('pins loopback http for local e2e', () => {
    expect(supabaseConnectSrcTokens('http://127.0.0.1:54321')).toEqual({
      https: 'http://127.0.0.1:54321',
      wss: 'ws://127.0.0.1:54321',
    });
  });

  it('rejects empty, wildcards, and the deployed placeholder host', () => {
    expect(supabaseConnectSrcTokens('')).toBeNull();
    expect(supabaseConnectSrcTokens('https://*.supabase.co')).toBeNull();
    expect(supabaseConnectSrcTokens('https://[REDACTED].supabase.co')).toBeNull();
    expect(supabaseConnectSrcTokens('http://evil.example')).toBeNull();
  });

  it('throws when a production build has nothing to pin', () => {
    expect(() => requireSupabaseConnectSrcTokens('')).toThrow(/pin-able origin/);
  });
});

describe('applySupabaseConnectSrc', () => {
  it('inserts https and wss after connect-src self', () => {
    const tokens = requireSupabaseConnectSrcTokens('https://abcd.supabase.co');
    const next = applySupabaseConnectSrc(BASE_HEADERS, tokens);
    expect(next).toContain(
      "connect-src 'self' https://abcd.supabase.co wss://abcd.supabase.co https://accounts.google.com",
    );
    expect(next).not.toContain('[REDACTED]');
  });

  it('strips a previously shipped placeholder host', () => {
    const stale = `/*
  Content-Security-Policy: default-src 'self'; connect-src 'self' https://[REDACTED].supabase.co wss://[REDACTED].supabase.co https://accounts.google.com
`;
    const next = applySupabaseConnectSrc(
      stale,
      requireSupabaseConnectSrcTokens('https://abcd.supabase.co'),
    );
    expect(next).not.toContain('[REDACTED]');
    expect(next).toContain('https://abcd.supabase.co');
    expect(next).toContain('wss://abcd.supabase.co');
  });
});
