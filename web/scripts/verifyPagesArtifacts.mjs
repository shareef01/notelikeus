import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findSecretsInDir } from './bundleSecrets.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const distDir = join(root, 'web', 'dist');

/**
 * `--deployable` additionally rejects artifacts that are fine to build but must never be
 * published: a CSP pinned to loopback or to a stand-in host, or a backend secret key inlined into
 * the bundle.
 *
 * Off by default because CI's verify job deliberately builds with `placeholder.supabase.co` so
 * pull requests need no secrets, and that artifact is thrown away rather than deployed. Anything
 * that actually uploads a build passes the flag.
 */
const deployable = process.argv.includes('--deployable');

// captcha.html: the Android and desktop apps open it to pass Turnstile before a code is sent (D30).
const required = ['_headers', '_redirects', 'index.html', 'captcha.html'];

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

  // A secret key in VITE_SUPABASE_ANON_KEY is inlined into the bundle, and the deploy would put it
  // on a public URL (audit T-01). Only the file and the kind are printed, never the value.
  const secrets = findSecretsInDir(distDir);
  if (secrets.length > 0) {
    for (const { file, kinds } of secrets) {
      console.error(`web/dist/${file} contains a backend secret: ${kinds.join(', ')}`);
    }
    console.error(
      'Nothing was deployed. VITE_SUPABASE_ANON_KEY must be the publishable key (sb_publishable_…) ' +
        'or the legacy anon JWT. Fix the secret, and rotate the key if this build was ever published.',
    );
    process.exit(1);
  }

  // Ensure test-hooks like __NOTELIKEUS_NOTES_STORE__ are never present in production dist
  const distAssetsDir = join(distDir, 'assets');
  try {
    const assetFiles = readdirSync(distAssetsDir);
    for (const file of assetFiles) {
      if (file.endsWith('.js')) {
        const content = readFileSync(join(distAssetsDir, file), 'utf8');
        if (content.includes('__NOTELIKEUS_NOTES_STORE__')) {
          console.error(`web/dist/assets/${file} contains E2E test-hook __NOTELIKEUS_NOTES_STORE__!`);
          process.exit(1);
        }
      }
    }
  } catch (err) {
    console.error('Failed to read dist/assets for test-hook verification:', err);
    process.exit(1);
  }
}

console.log(
  deployable
    ? 'Cloudflare Pages artifacts verified in web/dist (deployable)'
    : 'Cloudflare Pages artifacts verified in web/dist',
);
