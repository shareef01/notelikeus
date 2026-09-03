#!/usr/bin/env node
/**
 * Build a Cloudflare Pages *staging* bundle (Supabase + R2) and deploy it to
 * notelikeus-dev. Does not set VITE_ALLOW_SUPABASE_PRODUCTION.
 *
 * Requires gitignored env files:
 *   web/.env          — Firebase web config (same as production Hosting)
 *   web/.env.staging  — from `npm run setup:staging`
 *
 * Usage:
 *   npm run deploy:staging-pages
 *
 * Deploys to the Pages project's production branch (`main`) so
 * https://notelikeus-dev.pages.dev updates. That URL is staging, not Firebase Hosting.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const WEB = resolve(ROOT, 'web');
const ENV_FILE = resolve(WEB, '.env');
const STAGING_FILE = resolve(WEB, '.env.staging');

function parseEnvFile(path) {
  const env = {};
  if (!existsSync(path)) return env;
  for (const rawLine of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    env[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return env;
}

function requireKeys(env, keys, source) {
  const missing = keys.filter((key) => !env[key]);
  if (missing.length > 0) {
    throw new Error(`Missing ${missing.join(', ')} in ${source}`);
  }
}

function run(command, args, extraEnv) {
  const result = spawnSync(command, args, {
    cwd: extraEnv.cwd ?? ROOT,
    env: { ...process.env, ...extraEnv.env },
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function main() {
  const firebase = parseEnvFile(ENV_FILE);
  const staging = parseEnvFile(STAGING_FILE);
  requireKeys(
    firebase,
    [
      'VITE_FIREBASE_API_KEY',
      'VITE_FIREBASE_AUTH_DOMAIN',
      'VITE_FIREBASE_PROJECT_ID',
      'VITE_FIREBASE_STORAGE_BUCKET',
      'VITE_FIREBASE_MESSAGING_SENDER_ID',
      'VITE_FIREBASE_APP_ID',
      'VITE_FIREBASE_GOOGLE_CLIENT_ID',
    ],
    'web/.env',
  );
  requireKeys(
    staging,
    ['VITE_REMOTE_BACKEND', 'VITE_SUPABASE_URL', 'VITE_SUPABASE_ANON_KEY'],
    'web/.env.staging',
  );
  if (staging.VITE_REMOTE_BACKEND !== 'supabase') {
    throw new Error('web/.env.staging must set VITE_REMOTE_BACKEND=supabase');
  }

  const buildEnv = {
    ...firebase,
    ...staging,
    VITE_ALLOW_SUPABASE_STAGING: 'true',
  };
  delete buildEnv.VITE_ALLOW_SUPABASE_PRODUCTION;

  console.log('Building Pages staging bundle (Supabase on *.pages.dev only)…');
  run('npm', ['run', 'build'], { cwd: WEB, env: buildEnv });
  run('node', [resolve(WEB, 'scripts/verifyPagesArtifacts.mjs')], { env: {} });

  // Pages project production branch is `main` → https://notelikeus-dev.pages.dev
  // (staging site). Other git branches become preview aliases and leave that
  // URL on the previous Firebase-default bundle.
  console.log('Deploying to Cloudflare Pages project notelikeus-dev (production alias)…');
  run(
    'npx',
    [
      'wrangler',
      'pages',
      'deploy',
      'web/dist',
      '--project-name=notelikeus-dev',
      '--branch=main',
    ],
    { env: {} },
  );
  console.log(
    'Staging Pages deploy complete. Firebase Hosting (notelike.web.app) is unchanged.',
  );
}

try {
  main();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
}
