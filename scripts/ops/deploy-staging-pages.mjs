#!/usr/bin/env node
/**
 * Build a Cloudflare Pages bundle (Supabase + R2) and deploy it to notelikeus-dev.
 *
 * Requires gitignored env files:
 *   web/.env.staging  — hosted Supabase URL, anon JWT, optional attachments worker
 *
 * Usage:
 *   npm run deploy:staging-pages
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const WEB = resolve(ROOT, 'web');
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
  const staging = parseEnvFile(STAGING_FILE);
  requireKeys(staging, ['VITE_SUPABASE_URL', 'VITE_SUPABASE_ANON_KEY'], 'web/.env.staging');
  if (staging.VITE_SUPABASE_ANON_KEY.startsWith('sb_')) {
    throw new Error('VITE_SUPABASE_ANON_KEY looks like a secret API key (sb_…). Use the public anon JWT (eyJ…).');
  }

  const buildEnv = { ...staging };
  console.log('Building Pages bundle…');
  run('npm', ['run', 'build'], { cwd: WEB, env: buildEnv });
  run('node', [resolve(WEB, 'scripts/verifyPagesArtifacts.mjs')], { env: {} });

  console.log('Deploying to Cloudflare Pages project notelikeus-dev (Production alias)…');
  run(
    'npx',
    [
      'wrangler',
      'pages',
      'deploy',
      'web/dist',
      '--project-name=notelikeus-dev',
      '--branch=main',
      '--commit-dirty=true',
    ],
    { env: {} },
  );
  console.log('Pages deploy complete.');
}

try {
  main();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
}
