import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The Data API exposure of the database, asserted rather than assumed (FU-4).
 *
 * PostgREST publishes every function in the schemas `supabase/config.toml` lists under
 * `[api] schemas`, to whoever holds EXECUTE on it. So "is this helper reachable from outside the
 * database?" is decided by two things that live in different files, and neither of them is the
 * SQL: the schema list, and which schema the definition names.
 *
 * `begin_sync_mutation()` and `end_sync_mutation()` were reachable that way — granted to
 * `authenticated` because the SECURITY INVOKER RPCs need them, and in `public` because that is
 * where they started. They now live in `notelikeus_internal`, which is not in the list. This test
 * is what keeps that true: adding the schema to the list, or reintroducing a `public` copy of a
 * guard helper, both fail here instead of quietly reopening the surface.
 */

function repoRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let depth = 0; depth < 12; depth++) {
    try {
      if (statSync(join(dir, 'supabase', 'config.toml')).isFile()) return dir;
    } catch {
      // keep walking up
    }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error('Could not locate supabase/config.toml from ' + import.meta.url);
}

const root = repoRoot();

/** The `[api] schemas` list, read the way the CLI reads it. */
function exposedSchemas(): string[] {
  const toml = readFileSync(join(root, 'supabase', 'config.toml'), 'utf8');
  const line = /^\s*schemas\s*=\s*\[(.*)\]\s*$/m.exec(toml);
  if (!line) throw new Error('no [api] schemas line in supabase/config.toml');
  return line[1]
    .split(',')
    .map((entry) => entry.trim().replace(/^["']|["']$/g, ''))
    .filter((entry) => entry.length > 0);
}

/** Every migration, in order, so a later definition wins the way the CLI replays them. */
function migrations(): string[] {
  const dir = join(root, 'supabase', 'migrations');
  return readdirSync(dir)
    .filter((file) => file.endsWith('.sql'))
    // oxlint wants `toSorted()`, which this tsconfig's lib does not have (the sibling
    // rpcSignatureContract.test.ts sorts the same list the same way).
    .sort()
    .map((file) => readFileSync(join(dir, file), 'utf8'));
}

describe('Supabase Data API exposure', () => {
  it('exposes only public and graphql_public', () => {
    // Not a style preference: this list *is* the API surface. A new entry needs a reviewer to
    // decide that every function in that schema may be called by anyone holding EXECUTE on it.
    expect(exposedSchemas()).toEqual(['public', 'graphql_public']);
  });

  it('keeps the sync-mutation guard helpers out of the exposed schema', () => {
    const exposed = exposedSchemas();
    const internal = 'notelikeus_internal';
    expect(exposed).not.toContain(internal);

    const helpers = ['begin_sync_mutation', 'end_sync_mutation', 'sync_mutation_guard'];
    // The last definition of each helper, and where it lives.
    for (const helper of helpers) {
      let schema: string | null = null;
      for (const sql of migrations()) {
        for (const match of sql.matchAll(
          new RegExp(`CREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\s+(public|${internal})\\.${helper}\\s*\\(`, 'g'),
        )) {
          schema = match[1];
        }
      }
      expect(schema, `${helper} is defined nowhere`).not.toBeNull();
      expect(schema, `${helper} must not be defined in an exposed schema`).toBe(internal);
    }
  });
});
