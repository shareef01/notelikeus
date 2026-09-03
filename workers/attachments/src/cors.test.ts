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
