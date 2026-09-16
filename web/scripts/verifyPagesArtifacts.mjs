import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const distDir = join(root, 'web', 'dist');

/**
 * `--deployable` additionally rejects artifacts that are fine to build but must never be
 * published: a CSP pinned to loopback or to a stand-in host.
 *
 * Off by default because CI's verify job deliberately builds with `placeholder.supabase.co` so
 * pull requests need no secrets, and that artifact is thrown away rather than deployed. Anything
 * that actually uploads a build passes the flag.
 */
const deployable = process.argv.includes('--deployable');

const required = ['_headers', '_redirects', 'index.html'];

for (const file of required) {
  const path = join(distDir, file);
  try {
    readFileSync(path);
  } catch {
    console.error(`Missing Pages artifact: web/dist/${file} (run "cd web && npm run build" first)`);
    process.exit(1);
  }
}

const redirects = readFileSync(join(distDir, '_redirects'), 'utf8');
if (!redirects.includes('/index.html')) {
  console.error('web/dist/_redirects must SPA-fallback to /index.html');
  process.exit(1);
}

const headers = readFileSync(join(distDir, '_headers'), 'utf8');
if (headers.includes('https://*.workers.dev') || headers.includes('https://*.supabase.co')) {
  console.error('web/dist/_headers must not wildcard workers.dev or supabase.co');
  process.exit(1);
}
if (headers.includes('[REDACTED]')) {
  console.error('web/dist/_headers still names the [REDACTED] placeholder host');
  process.exit(1);
}
if (!headers.includes('.supabase.co') && !headers.includes('127.0.0.1') && !headers.includes('localhost')) {
  console.error('web/dist/_headers must pin a Supabase connect-src host from VITE_SUPABASE_URL');
  process.exit(1);
}

if (deployable) {
  // A build is only publishable if the origins it is allowed to talk to are the real ones. The
  // failure this catches is silent in the browser: the site loads, and every Supabase request is
  // refused by the CSP with nothing in the UI to say why.
  const connectSrc = /connect-src([^;\n]*)/.exec(headers)?.[1] ?? '';
  const unpublishable = [
    ['127.0.0.1', 'loopback'],
    ['localhost', 'loopback'],
    ['placeholder.supabase.co', 'placeholder'],
    ['example.supabase.co', 'placeholder'],
    ['YOUR_PROJECT', 'placeholder'],
  ].filter(([needle]) => connectSrc.includes(needle));

  if (unpublishable.length > 0) {
    const names = unpublishable.map(([needle, kind]) => `${needle} (${kind})`).join(', ');
    console.error(
      `web/dist/_headers pins connect-src to ${names}, so the deployed site could not reach ` +
        'Supabase. Rebuild with the production VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY.',
    );
    process.exit(1);
  }
}

console.log(
  deployable
    ? 'Cloudflare Pages artifacts verified in web/dist (deployable)'
    : 'Cloudflare Pages artifacts verified in web/dist',
);
