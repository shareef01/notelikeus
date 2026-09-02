import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const firebase = JSON.parse(readFileSync(join(root, 'firebase.json'), 'utf8'));
const pagesHeaders = readFileSync(join(root, 'web', 'public', '_headers'), 'utf8');

const firebaseGlobal = firebase.hosting.headers.find((entry) => entry.source === '**');
if (!firebaseGlobal) {
  console.error('firebase.json is missing the global hosting headers block');
  process.exit(1);
}

const firebaseByKey = Object.fromEntries(
  firebaseGlobal.headers.map((header) => [header.key, header.value]),
);

const requiredKeys = [
  'X-Content-Type-Options',
  'Strict-Transport-Security',
  'Referrer-Policy',
  'X-Frame-Options',
  'Permissions-Policy',
  'Content-Security-Policy',
];

for (const key of requiredKeys) {
  if (!pagesHeaders.includes(`${key}:`)) {
    console.error(`web/public/_headers is missing ${key}`);
    process.exit(1);
  }
}

for (const key of requiredKeys) {
  if (key === 'Content-Security-Policy') continue;
  const expected = firebaseByKey[key];
  if (!expected || !pagesHeaders.includes(expected)) {
    console.error(`web/public/_headers ${key} does not match firebase.json`);
    process.exit(1);
  }
}

const firebaseCsp = firebaseByKey['Content-Security-Policy'] ?? '';
const connectSrc = firebaseCsp.match(/connect-src [^;]+/)?.[0] ?? '';
for (const token of connectSrc.split(/\s+/).slice(1)) {
  if (!pagesHeaders.includes(token)) {
    console.error(`Pages CSP connect-src is missing Firebase token: ${token}`);
    process.exit(1);
  }
}

const migrationTokens = ['https://*.supabase.co', 'wss://*.supabase.co', 'https://*.workers.dev'];
for (const token of migrationTokens) {
  if (!pagesHeaders.includes(token)) {
    console.error(`Pages CSP is missing migration token: ${token}`);
    process.exit(1);
  }
}

console.log('Cloudflare Pages headers are a superset of firebase.json hosting headers');
