import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const envPath = join(dirname(fileURLToPath(import.meta.url)), '..', '.env.e2e');
const env = Object.fromEntries(
  readFileSync(envPath, 'utf8')
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#') && line.includes('='))
    .map((line) => {
      const eq = line.indexOf('=');
      return [line.slice(0, eq).trim(), line.slice(eq + 1).trim()];
    }),
);

const url = env.VITE_SUPABASE_URL || '';
let host = '';
try {
  host = new URL(url).hostname.toLowerCase();
} catch {
  console.error(`E2E refused: VITE_SUPABASE_URL is not a valid URL (${url || 'empty'})`);
  process.exit(1);
}

if (host !== '127.0.0.1' && host !== 'localhost' && host !== '::1') {
  console.error(`E2E refused: Supabase host must be localhost, got ${host}`);
  process.exit(1);
}

if (env.VITE_E2E !== '1') {
  console.error('E2E refused: web/.env.e2e must set VITE_E2E=1');
  process.exit(1);
}

console.log(`E2E target is local Supabase at ${url}`);
