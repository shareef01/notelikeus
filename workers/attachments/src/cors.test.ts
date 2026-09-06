import { describe, expect, it } from 'vitest';
import { attachmentCorsHeaders, isAllowedAttachmentOrigin } from './cors';

describe('attachment CORS', () => {
  it('allows local Vite origins', () => {
    expect(isAllowedAttachmentOrigin('http://localhost:5173')).toBe(true);
    expect(isAllowedAttachmentOrigin('http://127.0.0.1:5173')).toBe(true);
  });

  it('rejects non-local origins', () => {
    expect(isAllowedAttachmentOrigin('https://notelike.web.app')).toBe(false);
    expect(isAllowedAttachmentOrigin('')).toBe(false);
    expect(isAllowedAttachmentOrigin('not-a-url')).toBe(false);
  });

  it('allows Cloudflare Pages preview hosts for this project only', () => {
    expect(isAllowedAttachmentOrigin('https://notelikeus-dev.pages.dev')).toBe(true);
    expect(isAllowedAttachmentOrigin('https://abc.notelikeus-dev.pages.dev')).toBe(true);
    expect(isAllowedAttachmentOrigin('https://evil.pages.dev')).toBe(false);
    expect(isAllowedAttachmentOrigin('https://pages.dev')).toBe(false);
  });

  it('allows extra Origins from the worker env list', () => {
    expect(
      isAllowedAttachmentOrigin('https://notes.example.com', 'https://notes.example.com'),
    ).toBe(true);
    expect(
      isAllowedAttachmentOrigin('https://notes.example.com', 'https://other.example.com'),
    ).toBe(false);
  });

  it('echoes an allowed Origin on preflight headers', () => {
    const headers = attachmentCorsHeaders(
      new Request('https://worker.example/v1/attachments/1/a', {
        headers: { Origin: 'http://127.0.0.1:5173' },
      }),
    );
    expect(headers['Access-Control-Allow-Origin']).toBe('http://127.0.0.1:5173');
    expect(headers['Access-Control-Allow-Headers']).toContain('Authorization');
  });
});
