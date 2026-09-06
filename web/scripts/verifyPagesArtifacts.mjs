import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const distDir = join(root, 'web', 'dist');

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

const manifestCandidates = ['manifest.webmanifest', 'manifest.json'];
let manifestPath = null;
for (const file of manifestCandidates) {
  const path = join(distDir, file);
  try {
    readFileSync(path);
    manifestPath = path;
    break;
  } catch {
    // try next
  }
}
if (!manifestPath) {
  console.error('web/dist must include a web app manifest with icon sizes');
  process.exit(1);
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const icons = Array.isArray(manifest.icons) ? manifest.icons : [];
const icon192 = icons.find((icon) => String(icon.src).includes('icon-192'));
const icon512 = icons.find((icon) => String(icon.src).includes('icon-512'));
if (icon192?.sizes !== '192x192') {
  console.error('icon-192.png must declare sizes 192x192');
  process.exit(1);
}
if (icon512?.sizes !== '512x512') {
  console.error('icon-512.png must declare sizes 512x512');
  process.exit(1);
}
if (String(icon192?.purpose ?? 'any').includes('maskable') || String(icon512?.purpose ?? 'any').includes('maskable')) {
  console.error('do not declare maskable icons unless the asset is a maskable-safe image');
  process.exit(1);
}
const shortcutIcons = (Array.isArray(manifest.shortcuts) ? manifest.shortcuts : []).flatMap(
  (shortcut) => (Array.isArray(shortcut.icons) ? shortcut.icons : []),
);
for (const icon of shortcutIcons) {
  if (String(icon.src).includes('icon-192') && icon.sizes !== '192x192') {
    console.error('shortcut icon-192.png must declare sizes 192x192');
    process.exit(1);
  }
}

console.log('Cloudflare Pages artifacts verified in web/dist');
