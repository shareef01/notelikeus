import 'fake-indexeddb/auto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The argument objects the web client sends must name a function the database actually has.
 *
 * PostgREST resolves `/rpc/<name>` by the *set of argument names*: an extra key, or a missing one
 * without a default, matches no overload and the call fails with PGRST202 before any SQL runs. A
 * mocked client that answers any body hides that completely — which is how the Kotlin client came to
 * send `restore_note` an argument it does not have (F-01). This is the web half of that guard; the
 * Kotlin half is `RpcSignatureContractTest`. Signatures come from the migrations, bodies from the real
 * web functions.
 */
const sent: Array<{ name: string; args: Record<string, unknown> }> = [];

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    rpc: async (name: string, args: Record<string, unknown> = {}) => {
      sent.push({ name, args });
      return { data: responses[name] ?? { status: 'applied', revision: 2 }, error: null };
    },
    auth: { getSession: async () => ({ data: { session: { user: { id: USER } } }, error: null }) },
  }),
  isSupabaseBackendEnabled: () => true,
}));

import { purgeDeletedNoteAttachment } from '@/lib/attachments/supabaseAttachmentMetadata';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { restoreCloudNote } from '@/lib/notes/tombstones';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import {
  applyNoteChange,
  fetchSnapshotNotes,
  pullIncrementalChanges,
} from '@/lib/supabase/supabaseSyncEngine';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

const USER = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const responses: Record<string, unknown> = {
  fetch_full_snapshot: { notes: [], tombstones: [], note_count: 0 },
  pull_changes: { changes: [], has_more: false },
};

interface Overload {
  required: Set<string>;
  all: Set<string>;
}

function migrationsDir(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let depth = 0; depth < 12; depth++) {
    const candidate = join(dir, 'supabase', 'migrations');
    try {
      if (statSync(candidate).isDirectory()) return candidate;
    } catch {
      // keep walking up
    }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error('Could not locate supabase/migrations from ' + import.meta.url);
}

/** The text between the `(` at [open] and its matching `)`. */
function parenthesised(sql: string, open: number): string {
  let depth = 0;
  for (let i = open; i < sql.length; i++) {
    if (sql[i] === '(') depth++;
    if (sql[i] === ')' && --depth === 0) return sql.slice(open + 1, i);
  }
  throw new Error(`unbalanced parameter list at offset ${open}`);
}

function splitTopLevel(list: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < list.length; i++) {
    if (list[i] === '(') depth++;
    else if (list[i] === ')') depth--;
    else if (list[i] === ',' && depth === 0) {
      parts.push(list.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(list.slice(start));
  return parts.map((part) => part.trim()).filter((part) => part.length > 0);
}

const DEFAULT = /\bDEFAULT\b|=/i;

function typeOf(param: string, named: boolean): string {
  const tokens = param.split(DEFAULT)[0]!.trim().split(/\s+/);
  return (named && tokens.length > 1 ? tokens.slice(1) : tokens).join(' ').toUpperCase();
}

/**
 * Every `public` function overload the migrations leave behind, replayed in migration order: a later
 * `CREATE OR REPLACE` of the same argument types replaces an overload and `DROP FUNCTION` removes it.
 */
function deployedOverloads(): Map<string, Overload[]> {
  const byIdentity = new Map<string, { name: string; overload: Overload }>();
  const dir = migrationsDir();
  const files = readdirSync(dir).filter((file) => file.endsWith('.sql')).sort();
  const pattern = /(CREATE\s+(?:OR\s+REPLACE\s+)?|DROP\s+)FUNCTION\s+(?:IF\s+EXISTS\s+)?public\.(\w+)\s*\(/gi;
  for (const file of files) {
    const sql = readFileSync(join(dir, file), 'utf8');
    for (const match of sql.matchAll(pattern)) {
      const isCreate = match[1]!.toUpperCase().startsWith('CREATE');
      const name = match[2]!.toLowerCase();
      const params = splitTopLevel(parenthesised(sql, match.index! + match[0].length - 1));
      const identity = `${name}(${params.map((param) => typeOf(param, isCreate)).join(',')})`;
      if (!isCreate) {
        byIdentity.delete(identity);
        continue;
      }
      const firstWord = (param: string) => param.trim().split(/\s+/)[0]!.toLowerCase();
      byIdentity.set(identity, {
        name,
        overload: {
          all: new Set(params.map(firstWord)),
          required: new Set(params.filter((param) => !DEFAULT.test(param)).map(firstWord)),
        },
      });
    }
  }
  const result = new Map<string, Overload[]>();
  for (const { name, overload } of byIdentity.values()) {
    result.set(name, [...(result.get(name) ?? []), overload]);
  }
  return result;
}

const overloads = deployedOverloads();

function expectResolvable(name: string) {
  const call = sent.find((entry) => entry.name === name);
  expect(call, `${name} was never called`).toBeDefined();
  const keys = Object.keys(call!.args);
  const candidates = overloads.get(name) ?? [];
  const matches = candidates.some(
    (overload) =>
      [...overload.required].every((key) => keys.includes(key)) &&
      keys.every((key) => overload.all.has(key)),
  );
  expect(
    matches,
    `public.${name} has no overload accepting ${JSON.stringify(keys.sort())}; deployed: ` +
      JSON.stringify(candidates.map((overload) => [...overload.all].sort())),
  ).toBe(true);
}

const note = () => ({ ...createEmptyNote({ id: '42', localId: 42, title: 't' }), content: 'b' });

describe('web RPC arguments match a deployed signature', () => {
  beforeEach(async () => {
    sent.length = 0;
    useTombstoneStore.getState().reset();
    await resetNotesDatabaseForTests();
  });

  it('apply_note_change', async () => {
    await applyNoteChange(USER, note(), null);
    expectResolvable('apply_note_change');
  });

  it('restore_note', async () => {
    await restoreCloudNote(USER, note());
    expectResolvable('restore_note');
  });

  it('apply_note_delete', async () => {
    await supabaseRemoteNotesDataSource.deleteNote(USER, '42', 5);
    expectResolvable('apply_note_delete');
  });

  it('pull_changes', async () => {
    await pullIncrementalChanges(USER, new Map());
    expectResolvable('pull_changes');
  });

  it('fetch_full_snapshot', async () => {
    await fetchSnapshotNotes({ hydrateAttachments: false });
    expectResolvable('fetch_full_snapshot');
  });

  it('purge_deleted_note_attachment', async () => {
    await purgeDeletedNoteAttachment('att-1', '42');
    expectResolvable('purge_deleted_note_attachment');
  });
});
