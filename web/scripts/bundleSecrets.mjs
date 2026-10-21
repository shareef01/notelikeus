import { readdirSync, readFileSync } from 'node:fs';
import { extname, join, relative } from 'node:path';

/**
 * Backend credentials that must never be published in the Pages bundle (audit T-01).
 *
 * Vite inlines every `VITE_*` value into `dist/assets/*.js`. `bootstrap.ts` refuses to *use* a
 * secret key at runtime, but by then the key is on a public URL. The build workflows only check
 * that `VITE_SUPABASE_ANON_KEY` is set, so a secret key pasted into that GitHub secret would ship.
 * Scanning the output rather than the env also catches a secret that reaches the bundle by any
 * other route.
 *
 * Found: Supabase secret API keys (`sb_secret_…`) and legacy JWTs whose payload claims
 * `role: service_role`. The publishable key and the anon JWT are public and pass.
 */

// Real secret keys are `sb_secret_` plus about 30 characters. The minimum length keeps prose such
// as BootGate's "never an sb_secret_… key" from matching.
const SECRET_KEY = /sb_secret_[A-Za-z0-9_-]{16,}/;
const JWT = /eyJ[A-Za-z0-9_-]+\.(eyJ[A-Za-z0-9_-]+)\.[A-Za-z0-9_-]+/g;

/** Text files Pages serves. Images and fonts are skipped. */
const TEXT_EXTENSIONS = new Set(['.js', '.mjs', '.css', '.html', '.json', '.map', '.webmanifest', '.txt', '.xml', '.svg', '']);

function jwtRole(payload) {
  try {
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return claims && typeof claims === 'object' && typeof claims.role === 'string' ? claims.role : null;
  } catch {
    return null;
  }
}

/**
 * The kinds of secret found in `text`, at most one entry per kind. The values themselves are
 * never returned, so a failing check cannot print the secret into a public CI log.
 */
export function findSecretsInText(text) {
  const found = new Set();
  if (SECRET_KEY.test(text)) found.add('Supabase secret API key (sb_secret_…)');
  for (const match of text.matchAll(JWT)) {
    if (jwtRole(match[1]) === 'service_role') {
      found.add('service_role JWT');
      break;
    }
  }
  return [...found];
}

function* textFiles(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* textFiles(path);
    else if (entry.isFile() && TEXT_EXTENSIONS.has(extname(entry.name).toLowerCase())) yield path;
  }
}

/** `{ file, kinds }` for every text file under `dir` that holds a secret; `file` is relative to `dir`. */
export function findSecretsInDir(dir) {
  const results = [];
  for (const path of textFiles(dir)) {
    const kinds = findSecretsInText(readFileSync(path, 'utf8'));
    if (kinds.length > 0) results.push({ file: relative(dir, path).replaceAll('\\', '/'), kinds });
  }
  return results;
}
