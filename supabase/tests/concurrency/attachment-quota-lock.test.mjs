/**
 * Proves attachment quota enforcement is safe under concurrent finalization.
 *
 * pgTAP runs one session, so it cannot express this: the defect is two transactions each reading
 * the same pre-insert totals, both concluding they fit, and together exceeding the limit. This
 * drives two real psql sessions instead.
 *
 * The shape is deterministic in both directions, rather than sampling a race:
 *
 *   - Session A opens a transaction, finalizes the 20th attachment, and holds the transaction
 *     open. With the per-owner advisory lock in place it is still holding that lock.
 *   - Session B then finalizes a 21st. Serialised, it waits for A, sees 20 live attachments and
 *     is rejected. Unserialised, it reads A's uncommitted state as 19, decides it fits, and
 *     inserts — leaving 21.
 *
 * So a correct build ends with exactly 20 and B rejected; a build without the lock ends with 21.
 */
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const DB_URL =
  process.env.SUPABASE_DB_URL ?? 'postgresql://postgres:postgres@127.0.0.1:54322/postgres';
const OWNER = '5f9c1b2e-3d4a-4b5c-8d6e-7f8a9b0c1d2e';
const NOTE_ID = '900';
const PER_NOTE_LIMIT = 20;

/** Sets the request claims the RPCs read, exactly as PostgREST would for this user. */
const AS_USER = `
  set local role authenticated;
  select set_config('request.jwt.claims',
    '{"sub":"${OWNER}","role":"authenticated"}', true);
`;

async function psql(sql) {
  const { stdout } = await execFileAsync('psql', [DB_URL, '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c', sql], {
    maxBuffer: 10 * 1024 * 1024,
  });
  return stdout.trim();
}

async function havePsql() {
  try {
    await execFileAsync('psql', ['--version']);
    return true;
  } catch {
    return false;
  }
}

async function setUp() {
  // The owner has to exist: note_attachments.owner_id references auth.users.
  await psql(`
    insert into auth.users (instance_id, id, aud, role, email, encrypted_password, created_at, updated_at)
    values ('00000000-0000-0000-0000-000000000000', '${OWNER}', 'authenticated', 'authenticated',
            'quota-race@notelikeus.test', '', now(), now())
    on conflict (id) do nothing;
  `);

  await psql(`
    begin;
    ${AS_USER}
    select public.apply_note_change(
      '${NOTE_ID}', ${NOTE_ID}::bigint, null::bigint,
      'Quota race', 'body', 100::bigint, 0, false, false, false, 0, null::bigint,
      '[]'::jsonb, '[]'::jsonb
    );
    commit;
  `);

  // Fill to one below the per-note limit, so exactly one more may be added.
  for (let index = 1; index < PER_NOTE_LIMIT; index += 1) {
    await psql(`
      begin;
      ${AS_USER}
      select public.finalize_note_attachment_put(
        '${NOTE_ID}', 'fill${index}',
        public.expected_attachment_object_key('${OWNER}'::uuid, '${NOTE_ID}', 'fill${index}'),
        'image/png', 100
      );
      commit;
    `);
  }

  const filled = await liveCount();
  if (filled !== PER_NOTE_LIMIT - 1) {
    throw new Error(`setup expected ${PER_NOTE_LIMIT - 1} attachments, found ${filled}`);
  }
}

async function liveCount() {
  const out = await psql(
    `select count(*) from public.note_attachments
     where owner_id = '${OWNER}'::uuid and note_id = '${NOTE_ID}' and deleted_at is null;`,
  );
  return Number.parseInt(out, 10);
}

/** Starts a transaction that finalizes one attachment and then holds the lock open. */
function holdOpen(attachmentId, holdSeconds) {
  const child = spawn('psql', [DB_URL, '-v', 'ON_ERROR_STOP=1', '-q', '-f', '-'], {
    stdio: ['pipe', 'ignore', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', (chunk) => {
    stderr += String(chunk);
  });
  child.stdin.end(`
    begin;
    ${AS_USER}
    select public.finalize_note_attachment_put(
      '${NOTE_ID}', '${attachmentId}',
      public.expected_attachment_object_key('${OWNER}'::uuid, '${NOTE_ID}', '${attachmentId}'),
      'image/png', 100
    );
    select pg_sleep(${holdSeconds});
    commit;
  `);
  const done = new Promise((resolve) => child.on('close', (code) => resolve({ code, stderr })));
  return { done };
}

async function cleanUp() {
  await psql(`delete from auth.users where id = '${OWNER}'::uuid;`).catch(() => {});
}

async function main() {
  if (!(await havePsql())) {
    console.log('SKIP: psql is not available, cannot drive two concurrent sessions');
    process.exit(0);
  }

  await cleanUp();
  await setUp();

  // A takes the 20th slot and keeps its transaction — and therefore the owner lock — open.
  const sessionA = holdOpen('racea', 4);
  await new Promise((resolve) => setTimeout(resolve, 1200));

  // B asks for a 21st while A is still uncommitted.
  let sessionBRejected = false;
  try {
    await psql(`
      begin;
      ${AS_USER}
      select public.finalize_note_attachment_put(
        '${NOTE_ID}', 'raceb',
        public.expected_attachment_object_key('${OWNER}'::uuid, '${NOTE_ID}', 'raceb'),
        'image/png', 100
      );
      commit;
    `);
  } catch (error) {
    sessionBRejected = /too many attachments/i.test(String(error?.stderr ?? error));
    if (!sessionBRejected) throw error;
  }

  const a = await sessionA.done;
  if (a.code !== 0) throw new Error(`session A failed unexpectedly: ${a.stderr}`);

  const finalCount = await liveCount();
  await cleanUp();

  const failures = [];
  if (!sessionBRejected) {
    failures.push('session B was allowed past the quota while session A held the slot uncommitted');
  }
  if (finalCount !== PER_NOTE_LIMIT) {
    failures.push(`expected exactly ${PER_NOTE_LIMIT} live attachments, found ${finalCount}`);
  }

  if (failures.length > 0) {
    console.error('FAIL: attachment quota is not serialised under concurrency');
    for (const failure of failures) console.error(`  - ${failure}`);
    process.exit(1);
  }

  console.log(
    `ok - concurrent finalization is serialised: session B rejected, ${finalCount} attachments live`,
  );
}

main().catch(async (error) => {
  await cleanUp();
  console.error('FAIL:', error?.message ?? error);
  process.exit(1);
});
