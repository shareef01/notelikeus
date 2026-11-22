// End-to-end check of signing in and syncing in the real Tauri app, against a LOCAL Supabase stack.
// Signs in through the window with an emailed code read from the local mail catcher, writes a note,
// syncs, checks the row in the database, restarts, and signs out.
//
//   NLK_TEST_SUPABASE_URL=http://127.0.0.1:54326 NLK_TEST_SUPABASE_KEY=<local key> \
//   NLK_TEST_MAILPIT_URL=http://127.0.0.1:54324 node crates/nlk-tauri/e2e/drive-account.mjs [shots]
//
// The local stack sends at most two emails an hour; a run that hits the limit says so and exits 0.

import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const url = process.env.NLK_TEST_SUPABASE_URL;
const key = process.env.NLK_TEST_SUPABASE_KEY;
const mailpit = process.env.NLK_TEST_MAILPIT_URL;
/** Optional: a local build of web/captcha.html (Turnstile test key), to run the security check too. */
const captchaPage = process.env.NLK_TEST_CAPTCHA_PAGE_URL ?? null;
if (!url || !key || !mailpit) {
  console.log('skipped: set NLK_TEST_SUPABASE_URL, NLK_TEST_SUPABASE_KEY and NLK_TEST_MAILPIT_URL');
  process.exit(0);
}
if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(url)) {
  console.error(`refusing ${url}: this driver creates accounts and only runs against a local stack`);
  process.exit(1);
}

const here = dirname(fileURLToPath(import.meta.url));
const exe = join(here, '..', '..', '..', 'target', 'debug', 'notelikeus-rust.exe');
const shots = process.argv[2] ?? null;
const PORT = 9334;
const dataDir = mkdtempSync(join(tmpdir(), 'nlk-tauri-account-'));
const email = `nlk-tauri-ui-${Date.now()}@example.test`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const children = new Set();
let failures = 0;

function check(cond, what) {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${what}`);
  if (!cond) failures += 1;
}

const deadline = setTimeout(() => {
  console.error('FAIL the whole run took longer than 3 minutes');
  for (const c of children) c.kill();
  process.exit(1);
}, 180_000);

async function launch() {
  const child = spawn(exe, [], {
    env: {
      ...process.env,
      NLK_RUST_DATA_DIR: dataDir,
      NLK_RUST_DEVTOOLS_PORT: String(PORT),
      NLK_SUPABASE_URL: url,
      NLK_SUPABASE_KEY: key,
      ...(captchaPage ? { NLK_CAPTCHA_PAGE_URL: captchaPage } : {}),
    },
    stdio: 'ignore',
  });
  children.add(child);
  child.once('exit', () => children.delete(child));
  for (let i = 0; i < 60; i++) {
    await sleep(500);
    try {
      const pages = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = pages.find((p) => p.type === 'page');
      if (page) return { child, cdp: await connect(page.webSocketDebuggerUrl) };
    } catch { /* not up yet */ }
  }
  throw new Error('the app did not expose its WebView within 30 s');
}

async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let next = 1;
  const waiting = new Map();
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && waiting.has(msg.id)) {
      const { resolve, reject } = waiting.get(msg.id);
      waiting.delete(msg.id);
      if (msg.error) reject(new Error(msg.error.message)); else resolve(msg.result);
    }
  };
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = next++;
    waiting.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
  /** Runs `fn` (written in this file) in the page with `args` as real arguments, never spliced into code. */
  const call = async (fn, ...args) => {
    const { result: global } = await send('Runtime.evaluate', { expression: 'globalThis' });
    const r = await send('Runtime.callFunctionOn', {
      objectId: global.objectId,
      functionDeclaration: fn.toString(),
      arguments: args.map((value) => ({ value })),
      awaitPromise: true,
      returnByValue: true,
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'call failed');
    return r.result.value;
  };
  const key = (k, code, modifiers = 0, vk = 0) => Promise.all([
    send('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code, modifiers, windowsVirtualKeyCode: vk }),
    send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, modifiers, windowsVirtualKeyCode: vk }),
  ]);
  const type = (text) => send('Input.insertText', { text });
  const click = async (selector) => {
    const box = await call((sel) => {
      const r = document.querySelector(sel).getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    }, selector);
    for (const t of ['mousePressed', 'mouseReleased']) {
      await send('Input.dispatchMouseEvent', { type: t, x: box.x, y: box.y, button: 'left', clickCount: 1 });
    }
  };
  const shot = async (name) => {
    if (!shots) return;
    mkdirSync(shots, { recursive: true });
    const { data } = await send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(join(shots, `${name}.png`), Buffer.from(data, 'base64'));
  };
  return { ws, send, call, key, type, click, shot };
}

const text = (cdp, id) => cdp.call((elementId) => document.getElementById(elementId).textContent, id);
const visible = (cdp, id) => cdp.call((elementId) => document.getElementById(elementId).checkVisibility(), id);
const titles = (cdp) => cdp.call(() => [...document.querySelectorAll('.note-item .note-title')].map((e) => e.textContent));

async function waitFor(fn, what, ms = 10_000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (await fn()) return true;
    await sleep(150);
  }
  check(false, `timed out waiting for ${what}`);
  return false;
}

async function ready(cdp) {
  await waitFor(() => cdp.call(() => document.body?.dataset.ready === 'true'), 'the app to load', 15_000);
  await cdp.call(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
}

async function newNote(cdp, title, body) {
  let attempts = 0;
  while (!(await visible(cdp, 'editor-form')) && attempts < 20) {
    attempts += 1;
    await cdp.click('#new-note');
    await sleep(200);
  }
  await waitFor(async () => (await cdp.call(() => document.activeElement?.id)) === 'title', 'the title to take focus');
  await cdp.type(title);
  await cdp.key('Enter', 'Enter', 0, 13);
  await cdp.type(body);
}

async function closeLikeUser(child) {
  execFileSync('taskkill', ['/PID', String(child.pid)], { stdio: 'ignore' });
  const exited = await Promise.race([new Promise((r) => child.once('exit', () => r(true))), sleep(10_000).then(() => false)]);
  check(exited, 'closing the window ends the app');
}

/** The code from the newest email to `to`, read from the local mail catcher. */
async function emailedCode(to) {
  for (let i = 0; i < 60; i++) {
    await sleep(250);
    const found = await (await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${to}`)}`)).json();
    const id = found.messages?.[0]?.ID;
    if (!id) continue;
    const message = await (await fetch(`${mailpit}/api/v1/message/${id}`)).json();
    const code = `${message.Text ?? ''} ${message.HTML ?? ''}`.match(/\b\d{6}\b/)?.[0];
    if (code) return code;
  }
  return null;
}

/** The titles of this account's notes, straight from the local database. */
function rowsInDatabase() {
  const sql = `select coalesce(string_agg(n.title || ': ' || n.content, ' | ' order by n.title), '') from public.notes n join auth.users u on u.id = n.owner_id where u.email = '${email.replace(/'/g, "''")}'`;
  return execFileSync('docker', ['exec', 'supabase_db_notelikeus', 'psql', '-U', 'postgres', '-tAc', sql], { encoding: 'utf8' }).trim();
}

try {
  let { child, cdp } = await launch();
  await cdp.send('Page.enable');
  await ready(cdp);
  check(await visible(cdp, 'account'), 'with a project configured, the account panel shows');
  check(await visible(cdp, 'sign-in-open'), 'signed out: a sign-in button');

  await newNote(cdp, 'Guest note', 'stays on this device');
  await waitFor(async () => (await titles(cdp)).includes('Guest note'), 'the guest note to save');

  // Sign in through the dialog with an emailed code.
  await cdp.click('#sign-in-open');
  await waitFor(() => cdp.call(() => document.getElementById('sign-in').open), 'the sign-in dialog');
  await cdp.call(() => document.getElementById('email').focus());
  await cdp.type(email);
  await cdp.click('#send-code');
  if (captchaPage) {
    // The security check opens in its own window: a second page target on the same WebView2.
    const findCheck = async () => {
      const pages = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      return pages.find((p) => p.type === 'page' && p.url.includes('return=app') && p.url.includes('state='));
    };
    let target = null;
    await waitFor(async () => (target = await findCheck()) != null, 'the security check window to open', 15_000);
    check(target != null, 'Send code opens the security check window first');
    if (target == null) console.log(`     dialog says: ${await text(cdp, 'sign-in-error')}`);
    if (target && shots) {
      const check2 = await connect(target.webSocketDebuggerUrl);
      await sleep(1500);
      await check2.shot('account-00-security-check');
      check2.ws.close();
    }
    await waitFor(async () => (await findCheck()) == null, 'the security check window to close', 60_000);
    check((await findCheck()) == null, 'the window closes itself after handing back the token');
  }
  const sent = await Promise.race([
    (async () => { await waitFor(() => visible(cdp, 'code-step'), 'the code step', 15_000); return true; })(),
    sleep(15_000).then(() => false),
  ]);
  const dialogError = await text(cdp, 'sign-in-error');
  if (!sent && /too many|wait/i.test(dialogError)) {
    console.log(`skipped: the local stack's email limit is used up (${dialogError})`);
    process.exitCode = 0;
  } else {
    await cdp.shot('account-01-code-step');
    const code = await emailedCode(email);
    check(code != null, 'the code email arrived');
    await cdp.call(() => document.getElementById('code').focus());
    await cdp.type(code ?? '');
    await cdp.click('#verify');
    await waitFor(async () => (await text(cdp, 'mode-badge')) === 'Rust · synced', 'the window to switch to the account');
    check((await titles(cdp)).length === 0, 'the account starts empty: guest notes are not merged in');
    check((await text(cdp, 'account-line')).includes(email), 'the panel names the signed-in address');

    await newNote(cdp, 'From the Rust app', 'synced through the window');
    await waitFor(async () => (await titles(cdp)).includes('From the Rust app'), 'the account note to save');
    await waitFor(async () => /waiting to sync/.test(await text(cdp, 'sync-line')), 'the panel to show the edit waiting');
    check(/1 change waiting to sync/.test(await text(cdp, 'sync-line')), 'after a save the panel says the edit is waiting, not "Up to date"');
    await cdp.click('#sync-button');
    await waitFor(async () => (await text(cdp, 'sync-line')).startsWith('Up to date'), 'the sync to finish');
    const unsynced = await cdp.call(() => window.__TAURI__.core.invoke('list_notes').then((n) => n.filter((x) => x.unsynced).length));
    check(unsynced === 0, 'every note is confirmed by the server');
    check(rowsInDatabase() === 'From the Rust app: synced through the window', 'the database holds the note, for this account only');
    await cdp.shot('account-02-synced');

    // The sign-in survives a restart.
    await closeLikeUser(child);
    cdp.ws.close();
    ({ child, cdp } = await launch());
    await ready(cdp);
    check((await text(cdp, 'mode-badge')) === 'Rust · synced', 'still signed in after a restart');
    check(JSON.stringify(await titles(cdp)) === '["From the Rust app"]', "the account's notes are back");

    // Signing out shows the guest notes again; the account's notes stay sealed on disk.
    await cdp.click('#sign-out');
    await waitFor(async () => (await text(cdp, 'mode-badge')) === 'Rust · guest', 'signing out');
    check(JSON.stringify(await titles(cdp)) === '["Guest note"]', 'signed out: the guest notes are back');
    await closeLikeUser(child);
    cdp.ws.close();
  }
} catch (error) {
  console.error(error);
  failures += 1;
} finally {
  for (const c of children) c.kill();
  await sleep(500);
  clearTimeout(deadline);
  rmSync(dataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}

console.log(failures === 0 ? '\nall checks passed' : `\n${failures} check(s) failed`);
process.exit(failures === 0 ? 0 : 1);
