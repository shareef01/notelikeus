import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const pagesHeaders = readFileSync(join(root, 'web', 'public', '_headers'), 'utf8');

const requiredHeaders = {
  'X-Content-Type-Options': 'nosniff',
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains; preload',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
};

for (const [key, value] of Object.entries(requiredHeaders)) {
  if (!pagesHeaders.includes(`${key}:`)) {
    console.error(`web/public/_headers is missing ${key}`);
    process.exit(1);
  }
  if (!pagesHeaders.includes(value)) {
    console.error(`web/public/_headers ${key} does not contain expected value: ${value}`);
    process.exit(1);
  }
}

if (!pagesHeaders.includes('Content-Security-Policy:')) {
  console.error('web/public/_headers is missing Content-Security-Policy');
  process.exit(1);
}

const requiredCspTokens = [
  "default-src 'self'",
  "object-src 'none'",
  "frame-ancestors 'none'",
  'https://cqydlidescvmpfviwncf.supabase.co',
  'wss://cqydlidescvmpfviwncf.supabase.co',
  'https://*.workers.dev',
  'https://accounts.google.com',
];

for (const token of requiredCspTokens) {
  if (!pagesHeaders.includes(token)) {
    console.error(`Pages CSP is missing required token: ${token}`);
    process.exit(1);
  }
}

const forbiddenCspTokens = [
  'firebaseio.com',
  'firebaseapp.com',
  'firebaseappcheck',
  'recaptcha',
];

for (const token of forbiddenCspTokens) {
  if (pagesHeaders.includes(token)) {
    console.error(`Pages CSP still allows retired Firebase token: ${token}`);
    process.exit(1);
  }
}

console.log('Cloudflare Pages security headers verified');
