// End-to-end check of the real Tauri app: launches the debug build against a throwaway data folder,
// drives the WebView over the DevTools protocol with real input events, restarts it, and checks
// what was saved. Never touches %APPDATA%\NotelikeusRustExperimental or ~/.notelikeus.
//
//   cargo build -p nlk-tauri && node crates/nlk-tauri/e2e/drive.mjs [screenshot-dir]
//
// Node 22+ (global WebSocket). Windows only: WebView2 is the engine.

import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const exe = join(here, '..', '..', '..', 'target', 'debug', 'notelikeus-rust.exe');
const shots = process.argv[2] ?? null;
const PORT = 9333;
const dataDir = mkdtempSync(join(tmpdir(), 'nlk-tauri-e2e-'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
/** Every app instance this run started, so a failed check can never leave one running. */
const children = new Set();
const deadline = setTimeout(() => {
  console.error('FAIL the whole run took longer than 3 minutes');
  for (const c of children) c.kill();
  process.exit(1);
}, 180_000);

function check(cond, what) {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${what}`);
  if (!cond) failures += 1;
}

async function launch() {
  const child = spawn(exe, [], {
    env: { ...process.env, NLK_RUST_DATA_DIR: dataDir, NLK_RUST_DEVTOOLS_PORT: String(PORT) },
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
  child.kill();
  throw new Error('the app did not expose its WebView within 30 s');
}

async function connect(url) {
  const ws = new WebSocket(url);
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
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'evaluate failed');
    return r.result.value;
  };
  /**
   * Runs `fn` (a function written in this file) in the page with `args` passed as real arguments.
   * Values are never spliced into source text, so no value can change what code runs.
   */
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
    for (const type of ['mousePressed', 'mouseReleased']) {
      await send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 });
    }
  };
  const shot = async (name) => {
    if (!shots) return;
    mkdirSync(shots, { recursive: true });
    const { data } = await send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(join(shots, `${name}.png`), Buffer.from(data, 'base64'));
  };
  return { ws, send, evaluate, call, key, type, click, shot };
}

/** Closes the window the way the title bar's X does (WM_CLOSE), then waits for the process to exit. */
async function closeLikeUser(child) {
  // taskkill without /F posts WM_CLOSE, as the X button does; the pid goes as an argument, no shell.
  execFileSync('taskkill', ['/PID', String(child.pid)], { stdio: 'ignore' });
  const exited = await Promise.race([
    new Promise((r) => child.once('exit', () => r(true))),
    sleep(10_000).then(() => false),
  ]);
  check(exited, 'closing the window ends the app');
}

const listTitles = (cdp) => cdp.evaluate(
  `[...document.querySelectorAll('.note-item .note-title')].map((e) => e.textContent)`);
/**
 * Whether the element is actually rendered, hidden ancestors included. Not the `hidden` property (CSS can
 * override it) and not the element's own computed display (a hidden parent leaves that unchanged).
 */
const visible = (cdp, id) => cdp.call((elementId) => document.getElementById(elementId).checkVisibility(), id);

async function waitFor(fn, what, ms = 5000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (await fn()) return true;
    await sleep(100);
  }
  check(false, `timed out waiting for ${what}`);
  return false;
}

try {
  // --- First run ---------------------------------------------------------------------------------
  let { child, cdp } = await launch();
  await cdp.send('Page.enable');
  // The first page is about:blank, which is already 'complete': wait for the app's own flag.
  await waitFor(() => cdp.evaluate(`document.body?.dataset.ready === 'true'`), 'the app to load', 15000);
  check(await visible(cdp, 'empty-list'), 'an empty library says how to start');
  // WebView2 drops input sent before its first frames are presented: let two frames pass, then retry
  // the first keystroke until it lands. Later input is sent once and must work first time.
  await cdp.evaluate('new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))');
  let attempts = 0;
  while (!(await visible(cdp, 'editor-form')) && attempts < 20) {
    attempts += 1;
    await cdp.key('n', 'KeyN', 2, 78); // Ctrl+N
    await sleep(250);
  }
  console.log(`     first keystroke landed after ${attempts} attempt(s)`);
  await cdp.shot('01-empty');

  await waitFor(() => visible(cdp, 'editor-form'), 'the editor to open');
  check(!(await visible(cdp, 'editor-empty')), 'the "select a note" placeholder is gone while the editor is open');
  check(!(await visible(cdp, 'toast')), 'no toast is shown before there is anything to say');
  await cdp.type('Trip');
  await cdp.key('Enter', 'Enter', 0, 13);
  await cdp.type('pack bags');
  await waitFor(async () => (await listTitles(cdp)).includes('Trip'), 'the first note to be saved');
  check((await cdp.evaluate(`document.activeElement.id`)) === 'content', 'Enter in the title moves to the note body');

  await cdp.click('#new-note');
  await waitFor(async () => (await cdp.evaluate(`document.activeElement.id`)) === 'title', 'New note to focus the title');
  await cdp.type('Groceries');
  await cdp.key('Enter', 'Enter', 0, 13);
  await cdp.type('milk, eggs');
  await waitFor(async () => (await listTitles(cdp)).length === 2, 'the second note to be saved');
  check(JSON.stringify(await listTitles(cdp)) === '["Groceries","Trip"]', 'newest note first');

  await cdp.key('f', 'KeyF', 2, 70); // Ctrl+F
  await cdp.type('milk');
  await sleep(200);
  check(JSON.stringify(await listTitles(cdp)) === '["Groceries"]', 'search filters the list');
  await cdp.type('zzz');
  await sleep(200);
  check(await visible(cdp, 'no-matches'), 'a search with no results says so');
  await cdp.evaluate(`(() => { const s = document.getElementById('search'); s.value = ''; s.dispatchEvent(new Event('input')); })()`);

  // Pin the older note: it moves to the top.
  await cdp.evaluate(`[...document.querySelectorAll('.note-item')].find((b) => b.textContent.includes('Trip')).click()`);
  await cdp.click('#pin');
  await waitFor(async () => (await listTitles(cdp))[0] === '📌 Trip', 'the pinned note to move to the top');

  // Delete, then Undo.
  await cdp.evaluate(`[...document.querySelectorAll('.note-item')].find((b) => b.textContent.includes('Groceries')).click()`);
  await sleep(100);
  await cdp.click('#delete');
  await waitFor(() => visible(cdp, 'toast'), 'the delete toast');
  check(JSON.stringify(await listTitles(cdp)) === '["📌 Trip"]', 'the deleted note leaves the list');
  await cdp.shot('02-deleted-with-undo');
  await cdp.click('#toast-action');
  await waitFor(async () => (await listTitles(cdp)).includes('Groceries'), 'Undo to bring the note back');

  // An edit typed just before closing must survive the close (onCloseRequested waits for the save).
  await cdp.evaluate(`[...document.querySelectorAll('.note-item')].find((b) => b.textContent.includes('Trip')).click()`);
  await sleep(100);
  await cdp.evaluate(`(() => { const c = document.getElementById('content'); c.focus(); c.setSelectionRange(c.value.length, c.value.length); })()`);
  await cdp.type(', passport');
  await cdp.shot('03-editing');
  await closeLikeUser(child);
  cdp.ws.close();

  // --- Second run: everything is still there -----------------------------------------------------
  ({ child, cdp } = await launch());
  await waitFor(() => cdp.evaluate(`document.body?.dataset.ready === 'true'`), 'the app to load again', 15000);
  await waitFor(async () => (await listTitles(cdp)).length === 2, 'the notes after a restart');
  check(JSON.stringify(await listTitles(cdp)) === '["📌 Trip","Groceries"]', 'notes, order and pin survive a restart');
  const trip = await cdp.evaluate(`window.__TAURI__.core.invoke('list_notes').then((n) => n.find((x) => x.title === 'Trip').content)`);
  check(trip === 'pack bags, passport', `the edit typed right before closing was saved (got ${JSON.stringify(trip)})`);
  await cdp.evaluate(`[...document.querySelectorAll('.note-item')].find((b) => b.textContent.includes('Groceries')).click()`);
  await sleep(150);
  await cdp.shot('04-after-restart');

  // Narrow window: one pane at a time, with a way back to the list.
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 480, height: 700, deviceScaleFactor: 1, mobile: false });
  await cdp.click('#back');
  await sleep(150);
  check(await visible(cdp, 'note-list') && !(await visible(cdp, 'editor-form')), 'narrow: the list shows alone');
  await cdp.evaluate(`[...document.querySelectorAll('.note-item')].find((b) => b.textContent.includes('Trip')).click()`);
  await sleep(150);
  check(await visible(cdp, 'editor-form') && !(await visible(cdp, 'note-list')) && await visible(cdp, 'back'),
    'narrow: the editor shows alone, with a Back button');
  await cdp.shot('05-narrow-editor');
  await cdp.click('#back');
  await sleep(150);
  check(await visible(cdp, 'note-list'), 'narrow: Back returns to the list');
  check(await cdp.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'narrow: no horizontal scroll');
  await cdp.send('Emulation.clearDeviceMetricsOverride');

  // Note text written as HTML must stay text.
  await cdp.click('#new-note');
  await cdp.type('<img src=x onerror="document.title=\'pwned\'">');
  await waitFor(async () => (await listTitles(cdp)).length === 3, 'the markup note to save');
  check((await cdp.evaluate(`document.title`)) !== 'pwned' && (await cdp.evaluate(`document.querySelectorAll('.note-list img').length`)) === 0,
    'note text is rendered as text, never as HTML');

  await closeLikeUser(child);
  cdp.ws.close();

  // --- On disk ----------------------------------------------------------------------------------
  const sealed = readFileSync(join(dataDir, 'accounts', '__guest__', 'state.nlkr'));
  check(!sealed.includes(Buffer.from('passport')) && !sealed.includes(Buffer.from('Groceries')), 'no note text in plaintext on disk');
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
