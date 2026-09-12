import { describe, expect, it } from 'vitest';
import {
  applyAttachmentsConnectSrc,
  attachmentsConnectSrcOrigin,
} from '@/lib/hosting/attachmentsConnectSrc';

const BASE_HEADERS = `/*
  Content-Security-Policy: default-src 'self'; connect-src 'self' https://abcd.supabase.co wss://abcd.supabase.co https://*.workers.dev https://accounts.google.com
`;

describe('attachmentsConnectSrcOrigin', () => {
  it('returns null when attachments are off', () => {
    expect(attachmentsConnectSrcOrigin('')).toBeNull();
    expect(attachmentsConnectSrcOrigin('   ')).toBeNull();
  });

  it('pins the staging workers.dev host, not a platform wildcard', () => {
    expect(
      attachmentsConnectSrcOrigin(
        'https://notelikeus-attachments.notelikeus.workers.dev',
      ),
    ).toBe('https://notelikeus-attachments.notelikeus.workers.dev');
  });

  it('strips a path from the configured Worker URL', () => {
    expect(
      attachmentsConnectSrcOrigin('https://attachments.example.com/v1/put'),
    ).toBe('https://attachments.example.com');
  });

  it('allows loopback http for the local Worker', () => {
    expect(attachmentsConnectSrcOrigin('http://127.0.0.1:8787')).toBe(
      'http://127.0.0.1:8787',
    );
  });

  it('rejects non-loopback http and wildcard hosts', () => {
    expect(attachmentsConnectSrcOrigin('http://evil.example')).toBeNull();
    expect(attachmentsConnectSrcOrigin('https://*.workers.dev')).toBeNull();
    expect(attachmentsConnectSrcOrigin('not-a-url')).toBeNull();
  });
});

describe('applyAttachmentsConnectSrc', () => {
  it('drops the workers.dev wildcard when attachments are off', () => {
    const next = applyAttachmentsConnectSrc(BASE_HEADERS, null);
    expect(next).not.toContain('https://*.workers.dev');
    expect(next).toContain('wss://abcd.supabase.co https://accounts.google.com');
  });

  it('inserts the pinned Worker origin after the Supabase wss host', () => {
    const next = applyAttachmentsConnectSrc(
      BASE_HEADERS,
      'https://notelikeus-attachments.notelikeus.workers.dev',
    );
    expect(next).not.toContain('https://*.workers.dev');
    expect(next).toContain(
      'wss://abcd.supabase.co https://notelikeus-attachments.notelikeus.workers.dev https://accounts.google.com',
    );
  });
});
