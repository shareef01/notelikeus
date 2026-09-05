/**
 * Merge staging Supabase/R2 keys from web/.env.staging into gitignored local.properties.
 *
 * Android debug BuildConfig and desktop BackendConfig read these keys. Never writes a
 * production-allow flag — Firebase remains the default until an owner-authorized cutover.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = resolve(HERE, '../..');

export const STAGING_PROPERTY_KEYS = {
  supabaseUrl: 'notelikeus.supabaseUrl',
  supabaseAnonKey: 'notelikeus.supabaseAnonKey',
  attachmentsWorkerUrl: 'notelikeus.attachmentsWorkerUrl',
};

export const FORBIDDEN_PROPERTY_KEYS = [
  'notelikeus.allowSupabaseProduction',
  'NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION',
];

export function parseDotEnv(text) {
  const out = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const stripped = line.startsWith('export ') ? line.slice('export '.length).trim() : line;
    const eq = stripped.indexOf('=');
    if (eq <= 0) continue;
    const key = stripped.slice(0, eq).trim();
    let value = stripped.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    out[key] = value;
  }
  return out;
}

export function stagingPropertiesFromEnv(env) {
  const url = env.VITE_SUPABASE_URL?.trim();
  const anon = env.VITE_SUPABASE_ANON_KEY?.trim();
  const worker = env.VITE_ATTACHMENTS_WORKER_URL?.trim();
  if (!url) {
    throw new Error('web/.env.staging is missing VITE_SUPABASE_URL');
  }
  if (!anon) {
    throw new Error('web/.env.staging is missing VITE_SUPABASE_ANON_KEY');
  }
  if (anon.startsWith('sb_')) {
    throw new Error(
      'VITE_SUPABASE_ANON_KEY looks like a secret API key (sb_…). Use the public anon JWT (eyJ…).',
    );
  }
  if (!worker) {
    throw new Error('web/.env.staging is missing VITE_ATTACHMENTS_WORKER_URL');
  }
  return {
    [STAGING_PROPERTY_KEYS.supabaseUrl]: url,
    [STAGING_PROPERTY_KEYS.supabaseAnonKey]: anon,
    [STAGING_PROPERTY_KEYS.attachmentsWorkerUrl]: worker,
  };
}

export function mergeLocalProperties(existingText, updates) {
  const lines = existingText.length === 0 ? [] : existingText.split(/\r?\n/);
  if (lines.length > 0 && lines[lines.length - 1] === '') {
    lines.pop();
  }
  const used = new Set();
  const next = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      next.push(line);
      continue;
    }
    const eq = trimmed.indexOf('=');
    if (eq <= 0) {
      next.push(line);
      continue;
    }
    const key = trimmed.slice(0, eq).trim();
    if (Object.hasOwn(updates, key)) {
      next.push(`${key}=${updates[key]}`);
      used.add(key);
      continue;
    }
    next.push(line);
  }
  for (const [key, value] of Object.entries(updates)) {
    if (!used.has(key) && !FORBIDDEN_PROPERTY_KEYS.includes(key)) {
      next.push(`${key}=${value}`);
    }
  }
  return `${next.join('\n')}\n`;
}

export function writeKotlinStagingProperties({
  envText,
  existingPropertiesText = '',
} = {}) {
  const updates = stagingPropertiesFromEnv(parseDotEnv(envText));
  for (const key of FORBIDDEN_PROPERTY_KEYS) {
    delete updates[key];
  }
  return mergeLocalProperties(existingPropertiesText, updates);
}

function main() {
  const envPath = resolve(REPO_ROOT, 'web/.env.staging');
  const propertiesPath = resolve(REPO_ROOT, 'local.properties');
  if (!existsSync(envPath)) {
    console.error('Missing web/.env.staging. Run npm run setup:staging first.');
    process.exit(1);
  }
  const envText = readFileSync(envPath, 'utf8');
  const existing = existsSync(propertiesPath) ? readFileSync(propertiesPath, 'utf8') : '';
  const next = writeKotlinStagingProperties({
    envText,
    existingPropertiesText: existing,
  });
  writeFileSync(propertiesPath, next);
  console.log(
    'Updated gitignored local.properties with Kotlin staging keys (Android BuildConfig / desktop).',
  );
  console.log('Rebuild the Android APK after changing these keys.');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main();
}
