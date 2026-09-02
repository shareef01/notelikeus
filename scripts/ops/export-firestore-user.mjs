#!/usr/bin/env node
/**
 * Offline ops helper: convert a Firestore notes dump into a Notelikeus backup JSON.
 *
 * Does not talk to production. Provide a dump file:
 *
 *   {
 *     "uid": "FIREBASE_UID",
 *     "notes": [{ "id": "1", "title": "...", "content": "...", ...firestore fields }]
 *   }
 *
 * Usage:
 *   node scripts/ops/export-firestore-user.mjs --input dump.json --out backup.json
 *
 * The output is the same backup format the app already imports. Per-user import
 * into Supabase is still client-driven (Phase 6). This tool does not write to
 * Firestore or Supabase.
 */
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  buildBackupPayload,
  firestoreDocToBackupNote,
} from './firestoreToBackup.mjs';

function argValue(flag) {
  const index = process.argv.indexOf(flag);
  if (index === -1 || index === process.argv.length - 1) return null;
  return process.argv[index + 1];
}

function printHelp() {
  console.log(`Convert a Firestore notes dump to a Notelikeus backup JSON.

Usage:
  node scripts/ops/export-firestore-user.mjs --input dump.json --out backup.json

Dump shape:
  { "uid": "<firebase uid>", "notes": [{ "id": "1", ...firestore fields }] }

This script never contacts production Firebase or Supabase.
`);
}

async function main() {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    printHelp();
    return;
  }
  const inputPath = argValue('--input');
  const outPath = argValue('--out');
  if (!inputPath || !outPath) {
    printHelp();
    process.exitCode = 1;
    return;
  }

  const raw = JSON.parse(await readFile(resolve(inputPath), 'utf8'));
  const uid = typeof raw.uid === 'string' ? raw.uid : 'unknown';
  const docs = Array.isArray(raw.notes) ? raw.notes : [];
  const notes = docs.map((doc) => {
    const id = doc.id ?? doc.localId;
    const { id: _ignored, ...data } = doc;
    return firestoreDocToBackupNote(String(id), data);
  });
  const payload = buildBackupPayload(uid, notes);
  await writeFile(resolve(outPath), JSON.stringify(payload, null, 2), 'utf8');
  console.log(`Wrote ${notes.length} notes for ${uid} to ${outPath}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
