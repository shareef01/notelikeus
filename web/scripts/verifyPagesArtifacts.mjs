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

console.log('Cloudflare Pages artifacts verified in web/dist');
