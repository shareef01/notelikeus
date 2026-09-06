import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const publicDir = join(dirname(fileURLToPath(import.meta.url)), '../../../public');

describe('Cloudflare Pages static artifacts', () => {
  it('ships SPA fallback redirects', () => {
    const redirects = readFileSync(join(publicDir, '_redirects'), 'utf8');
    expect(redirects).toContain('/index.html');
  });

  it('ships security headers; Supabase connect-src is pinned at build time', () => {
    const headers = readFileSync(join(publicDir, '_headers'), 'utf8');
    expect(headers).toContain('X-Frame-Options: DENY');
    expect(headers).toContain("connect-src 'self'");
    expect(headers).toContain('https://accounts.google.com');
    expect(headers).not.toMatch(/https:\/\/[a-z0-9]+\.supabase\.co/);
    expect(headers).not.toContain('https://*.supabase.co');
    expect(headers).not.toContain('https://*.workers.dev');
    expect(headers).toContain('no-cache, no-store, must-revalidate');
  });
});
