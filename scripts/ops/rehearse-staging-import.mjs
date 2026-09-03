#!/usr/bin/env node
/**
 * Staging-only rehearsal: Firestore dump → backup JSON, then optional Supabase import.
 * Does not touch production Firebase or set cutover flags.
 *
 * Always writes scripts/ops/fixtures/backup.rehearsal.json (gitignored).
 * To also push that backup into staging Supabase:
 *
 *   REHEARSAL_EMAIL=… REHEARSAL_PASSWORD=… npm run rehearse:staging-import
 *
 * Otherwise sign in with Google at https://notelikeus-dev.pages.dev/ and use
 * Profile → Import backup with scripts/ops/fixtures/backup.rehearsal.example.json.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const { createClient } = createRequire(resolve(ROOT, 'web/package.json'))('@supabase/supabase-js');
const STAGING_ENV = resolve(ROOT, 'web/.env.staging');
const DUMP = resolve(ROOT, 'scripts/ops/fixtures/firestore-user-dump.json');
const BACKUP = resolve(ROOT, 'scripts/ops/fixtures/backup.rehearsal.json');

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

function runExport() {
  const result = spawnSync(
    process.execPath,
    [
      resolve(ROOT, 'scripts/ops/export-firestore-user.mjs'),
      '--input',
      DUMP,
      '--out',
      BACKUP,
    ],
    { cwd: ROOT, stdio: 'inherit' },
  );
  if (result.status !== 0) process.exit(result.status ?? 1);
}

function rpcArgsFromBackupNote(note) {
  const localId = Number(note.id ?? note.localId);
  const labels = Array.isArray(note.labels)
    ? note.labels.map((entry) =>
        typeof entry === 'string' ? { name: entry } : { name: String(entry?.name ?? '') },
      )
    : [];
  return {
    p_note_id: String(localId),
    p_local_id: localId,
    p_base_revision: null,
    p_title: String(note.title ?? ''),
    p_content: String(note.content ?? ''),
    p_client_timestamp: Number(note.timestamp ?? Date.now()),
    p_color: Number(note.color ?? 0),
    p_is_pinned: Boolean(note.isPinned),
    p_is_archived: Boolean(note.isArchived),
    p_is_trashed: Boolean(note.isTrashed),
    p_position: Number(note.position ?? 0),
    p_reminder_timestamp: note.reminderTimestamp ?? null,
    p_labels: labels.filter((row) => row.name),
    p_checklist: Array.isArray(note.checklist) ? note.checklist : [],
  };
}

async function main() {
  runExport();
  const backup = JSON.parse(readFileSync(BACKUP, 'utf8'));
  const notes = Array.isArray(backup.notes) ? backup.notes : [];
  if (notes.length === 0) throw new Error('Backup JSON contained no notes');

  const email = process.env.REHEARSAL_EMAIL?.trim();
  const password = process.env.REHEARSAL_PASSWORD;
  if (!email || !password) {
    console.log(
      JSON.stringify({
        exportNotes: notes.length,
        backup: 'scripts/ops/fixtures/backup.rehearsal.json',
        example: 'scripts/ops/fixtures/backup.rehearsal.example.json',
        cloudImport: 'skipped',
        next: 'Sign in with Google on https://notelikeus-dev.pages.dev/ then Profile → Import backup',
      }),
    );
    return;
  }

  const staging = parseEnvFile(STAGING_ENV);
  if (!staging.VITE_SUPABASE_URL || !staging.VITE_SUPABASE_ANON_KEY) {
    throw new Error('Missing VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY in web/.env.staging');
  }

  const supabase = createClient(staging.VITE_SUPABASE_URL, staging.VITE_SUPABASE_ANON_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) throw error;
  if (!data.session) throw new Error('Sign-in returned no session');

  let imported = 0;
  for (const note of notes) {
    const { data: rpcData, error: rpcError } = await supabase.rpc(
      'apply_note_change',
      rpcArgsFromBackupNote(note),
    );
    if (rpcError) throw rpcError;
    const status = typeof rpcData === 'object' && rpcData ? rpcData.status : rpcData;
    if (status !== 'ok' && status !== undefined) {
      throw new Error(`apply_note_change rejected: ${JSON.stringify(rpcData)}`);
    }
    imported += 1;
  }

  const { data: snapshot, error: snapError } = await supabase.rpc('fetch_full_snapshot');
  if (snapError) throw snapError;
  const cloudCount = Array.isArray(snapshot?.notes) ? snapshot.notes.length : 0;

  console.log(
    JSON.stringify({
      exportNotes: notes.length,
      imported,
      snapshotNotes: cloudCount,
    }),
  );
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
