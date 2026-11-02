// Account deletion end to end: real Supabase Auth and Postgres, the real Worker, local R2.
//
// The unit and pgTAP suites each stub the other side of this protocol. This is the one place the
// auth user really goes through the admin API with every migration's triggers in the way.
//
// Needs a local Supabase stack and `wrangler dev --test-scheduled` in workers/attachments. Run by
// .github/workflows/account-deletion-e2e.yml, which also sets API_URL, ANON_KEY and SERVICE_ROLE_KEY.
import { spawnSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const { API_URL, ANON_KEY, SERVICE_ROLE_KEY } = process.env;
if (!API_URL || !ANON_KEY || !SERVICE_ROLE_KEY) {
  throw new Error('API_URL, ANON_KEY and SERVICE_ROLE_KEY are required');
}
const WORKER_URL = process.env.WORKER_URL ?? 'http://127.0.0.1:8787';
const R2_BUCKET = process.env.R2_BUCKET ?? 'notelikeus-attachments-dev';
const WORKER_DIR = join(dirname(fileURLToPath(import.meta.url)), '../../workers/attachments');
const IMAGE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);

const adminHeaders = {
  apikey: SERVICE_ROLE_KEY,
  Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
  'Content-Type': 'application/json',
};
const userHeaders = (user) => ({
  apikey: ANON_KEY,
  Authorization: `Bearer ${user.token}`,
  'Content-Type': 'application/json',
});

let failures = 0;
function check(name, passed, detail = '') {
  console.log(`${passed ? 'ok  ' : 'FAIL'} ${name}${!passed && detail ? ` => ${detail}` : ''}`);
  if (!passed) failures += 1;
}

async function expectOk(response, what) {
  if (response.ok) return response;
  throw new Error(`${what} answered ${response.status}: ${await response.text()}`);
}

async function createUser(label) {
  const email = `${label}-${randomUUID()}@notelikeus.test`;
  // Local to this throwaway stack, and never printed.
  const password = randomBytes(24).toString('hex');
  const created = await expectOk(
    await fetch(`${API_URL}/auth/v1/admin/users`, {
      method: 'POST',
      headers: adminHeaders,
      body: JSON.stringify({ email, password, email_confirm: true }),
    }),
    `create ${label}`,
  );
  const { id } = await created.json();
  const session = await expectOk(
    await fetch(`${API_URL}/auth/v1/token?grant_type=password`, {
      method: 'POST',
      headers: { apikey: ANON_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    }),
    `sign in ${label}`,
  );
  return { label, id, token: (await session.json()).access_token };
}

/** Everything a real client leaves behind: a note, and an attachment with its object in R2. */
async function seedAccount(user) {
  const note = await expectOk(
    await fetch(`${API_URL}/rest/v1/rpc/apply_note_change`, {
      method: 'POST',
      headers: userHeaders(user),
      body: JSON.stringify({
        p_note_id: '1',
        p_local_id: 1,
        p_base_revision: null,
        p_title: 'title',
        p_content: 'body',
        p_client_timestamp: 100,
        p_color: 1,
        p_is_pinned: false,
        p_is_archived: false,
        p_is_trashed: false,
        p_position: 0,
        p_reminder_timestamp: null,
        p_labels: [],
        p_checklist: [],
      }),
    }),
    `note for ${user.label}`,
  );
  const status = (await note.json()).status;
  if (status !== 'applied') throw new Error(`note for ${user.label} was ${status}`);
  await expectOk(await attachment(user, 'PUT'), `upload for ${user.label}`);
}

function attachment(user, method) {
  return fetch(`${WORKER_URL}/v1/attachments/1/image-1`, {
    method,
    headers: { Authorization: `Bearer ${user.token}`, 'Content-Type': 'image/png' },
    ...(method === 'PUT' ? { body: IMAGE } : {}),
  });
}

const objectKey = (user, name = 'image-1') => `owners/${user.id}/notes/1/${name}`;

function wrangler(...args) {
  return spawnSync('npx', ['wrangler', 'r2', 'object', ...args, '--local'], {
    cwd: WORKER_DIR,
    encoding: 'utf8',
  });
}

/** Writes straight to R2, for bytes no row references: what an upload in flight can leave behind. */
function putStrayObject(user, name) {
  const file = join(mkdtempSync(join(tmpdir(), 'deletion-e2e-')), 'stray');
  writeFileSync(file, IMAGE);
  const result = wrangler('put', `${R2_BUCKET}/${objectKey(user, name)}`, '--file', file);
  if (result.status !== 0) throw new Error(`stray object: ${result.stderr}`);
}

function objectExists(user, name) {
  const file = join(mkdtempSync(join(tmpdir(), 'deletion-e2e-')), 'object');
  return wrangler('get', `${R2_BUCKET}/${objectKey(user, name)}`, '--file', file).status === 0;
}

async function rowCount(table, user) {
  const response = await expectOk(
    await fetch(`${API_URL}/rest/v1/${table}?owner_id=eq.${user.id}&select=owner_id`, {
      headers: adminHeaders,
    }),
    `read ${table}`,
  );
  return (await response.json()).length;
}

async function authUserExists(user) {
  const response = await fetch(`${API_URL}/auth/v1/admin/users/${user.id}`, { headers: adminHeaders });
  if (response.status === 404) return false;
  await expectOk(response, 'read auth user');
  return true;
}

async function checkAccountGone(user) {
  check(`${user.label}: auth user gone`, !(await authUserExists(user)));
  for (const table of ['notes', 'note_tombstones', 'note_attachments', 'sync_meta', 'account_sync_state']) {
    const rows = await rowCount(table, user);
    check(`${user.label}: no ${table} rows`, rows === 0, `${rows} rows`);
  }
  check(`${user.label}: object gone from R2`, !objectExists(user, 'image-1'));
}

async function runSweep() {
  await expectOk(
    await fetch(`${WORKER_URL}/__scheduled?cron=${encodeURIComponent('15 * * * *')}`),
    'scheduled run',
  );
}

async function waitFor(condition) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (await condition()) return true;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return false;
}

const workerUp = await waitFor(async () => {
  try {
    return (await fetch(`${WORKER_URL}/v1/account`)).status === 405;
  } catch {
    return false;
  }
});
if (!workerUp) throw new Error(`Worker not answering at ${WORKER_URL}`);

const deleted = await createUser('deleted');
const interrupted = await createUser('interrupted');
const bystander = await createUser('bystander');
for (const user of [deleted, interrupted, bystander]) await seedAccount(user);
putStrayObject(deleted, 'stray-1');

// The checks below prove nothing unless the same reads can see this data while it exists.
check('seed: note row visible', (await rowCount('notes', deleted)) === 1);
check('seed: attachment row visible', (await rowCount('note_attachments', deleted)) === 1);
check('seed: uploaded object visible in R2', objectExists(deleted, 'image-1'));
check('seed: stray object visible in R2', objectExists(deleted, 'stray-1'));

// A signed-in user deletes their account through the Worker.
const deletion = await fetch(`${WORKER_URL}/v1/account`, {
  method: 'DELETE',
  headers: { Authorization: `Bearer ${deleted.token}` },
});
const answer = await deletion.text();
check('DELETE /v1/account answers 200 deleted', deletion.status === 200 && JSON.parse(answer).status === 'deleted', `${deletion.status} ${answer}`);
await checkAccountGone(deleted);
check('stray object no row referenced is gone', !objectExists(deleted, 'stray-1'));

// A device still holding that user's token learns the account is gone, and cannot put data back.
const pull = await fetch(`${API_URL}/rest/v1/rpc/pull_changes`, {
  method: 'POST',
  headers: userHeaders(deleted),
  body: JSON.stringify({ p_after_revision: 0, p_limit: 10 }),
});
const pullBody = await pull.text();
check('old token: pull_changes answers NL003', pullBody.includes('NL003'), `${pull.status} ${pullBody}`);
const reupload = await attachment(deleted, 'PUT');
check('old token: upload refused', !reupload.ok, String(reupload.status));
check('old token: upload left no object', !objectExists(deleted, 'image-1'));
const repeat = await fetch(`${WORKER_URL}/v1/account`, {
  method: 'DELETE',
  headers: { Authorization: `Bearer ${deleted.token}` },
});
check('old token: repeat delete refused', repeat.status === 401, String(repeat.status));

// The Worker dying right after the database step leaves a request only the cron sweep can finish.
await expectOk(
  await fetch(`${API_URL}/rest/v1/rpc/request_account_deletion`, {
    method: 'POST',
    headers: userHeaders(interrupted),
    body: '{}',
  }),
  'request_account_deletion',
);
check('interrupted: auth user still there before the sweep', await authUserExists(interrupted));
check('interrupted: object still there before the sweep', objectExists(interrupted, 'image-1'));
// Bytes landing after the user is gone are the sweep's job too.
putStrayObject(deleted, 'stray-2');
await runSweep();
check('sweep finishes an interrupted deletion', await waitFor(async () => !(await authUserExists(interrupted))));
await checkAccountGone(interrupted);
check('sweep removes bytes that landed late', await waitFor(() => !objectExists(deleted, 'stray-2')));

// Nobody else's data moved.
check('bystander: auth user kept', await authUserExists(bystander));
check('bystander: note kept', (await rowCount('notes', bystander)) === 1);
check('bystander: attachment row kept', (await rowCount('note_attachments', bystander)) === 1);
const kept = await attachment(bystander, 'GET');
const keptBytes = kept.ok ? new Uint8Array(await kept.arrayBuffer()) : new Uint8Array();
check(
  'bystander: object still downloads intact',
  keptBytes.length === IMAGE.length && keptBytes.every((byte, i) => byte === IMAGE[i]),
  String(kept.status),
);

if (failures > 0) {
  console.error(`${failures} check(s) failed`);
  process.exit(1);
}
console.log('account deletion end to end: all checks passed');
