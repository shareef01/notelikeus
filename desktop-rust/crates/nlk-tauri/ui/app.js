// UI for the Rust experiment. Every note text is written with textContent or .value, never as HTML.
'use strict';

const invoke = window.__TAURI__.core.invoke;
const $ = (id) => document.getElementById(id);

const el = {
  app: $('app'), list: $('note-list'), emptyList: $('empty-list'), noMatches: $('no-matches'),
  search: $('search'), newNote: $('new-note'), form: $('editor-form'), editorEmpty: $('editor-empty'),
  title: $('title'), content: $('content'), pin: $('pin'), del: $('delete'), back: $('back'),
  status: $('status'), toast: $('toast'), toastText: $('toast-text'), toastAction: $('toast-action'),
  badge: $('mode-badge'), account: $('account'), accountLine: $('account-line'), syncLine: $('sync-line'),
  signInOpen: $('sign-in-open'), syncButton: $('sync-button'), signOut: $('sign-out'),
  dialog: $('sign-in'), emailStep: $('email-step'), codeStep: $('code-step'), email: $('email'), code: $('code'),
  codeSent: $('code-sent'), signInError: $('sign-in-error'), signInCancel: $('sign-in-cancel'),
  changeEmail: $('change-email'), sendCode: $('send-code'), verify: $('verify'),
};

const SAVE_DELAY_MS = 400;
const SAVED = 'Saved on this device. Guest mode never syncs.';
let notes = [];
/**
 * The note in the editor. A fresh object per note opened or drafted: a save captures it when the save
 * is *requested*, so switching notes never redirects a pending save, and a draft's first save
 * records the new id on it for the saves queued after it.
 */
let current = null;
let saveTimer = null;
/** One chain for every write, so a create and the edits typed after it land in order. */
let writes = Promise.resolve();

function setStatus(text) {
  el.status.textContent = text;
}

function showToast(text, action) {
  el.toastText.textContent = text;
  el.toastAction.hidden = !action;
  el.toastAction.onclick = action ? () => { hideToast(); action(); } : null;
  el.toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(hideToast, 6000);
}

function hideToast() {
  el.toast.hidden = true;
}

function formatTime(ms) {
  return new Date(ms).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function render() {
  const query = el.search.value.trim().toLowerCase();
  const shown = query
    ? notes.filter((n) => n.title.toLowerCase().includes(query) || n.content.toLowerCase().includes(query))
    : notes;
  el.list.replaceChildren(...shown.map((note) => {
    const li = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'note-item';
    button.dataset.id = note.id;
    if (current && note.id === current.id) button.setAttribute('aria-current', 'true');

    const title = document.createElement('span');
    title.className = note.title.trim() ? 'note-title' : 'note-title untitled';
    title.textContent = (note.isPinned ? '📌 ' : '') + (note.title.trim() || 'Untitled');
    const preview = document.createElement('span');
    preview.className = 'note-preview';
    preview.textContent = note.content;
    const meta = document.createElement('span');
    meta.className = 'note-meta';
    meta.textContent = formatTime(note.timestamp);

    button.append(title, preview, meta);
    button.addEventListener('click', () => openNote(note.id));
    li.append(button);
    return li;
  }));
  el.emptyList.hidden = notes.length > 0;
  el.noMatches.hidden = notes.length === 0 || shown.length > 0;
}

function showEditor(open) {
  el.form.hidden = !open;
  el.editorEmpty.hidden = open;
  el.app.dataset.view = open ? 'editor' : 'list';
}

function fillEditor(note) {
  el.title.value = note ? note.title : '';
  el.content.value = note ? note.content : '';
  setPinButton(Boolean(note && note.isPinned));
  el.pin.disabled = !note;
  el.del.disabled = !note;
}

function setPinButton(pinned) {
  el.pin.setAttribute('aria-pressed', String(pinned));
  el.pin.textContent = pinned ? 'Unpin' : 'Pin';
}

function upsertLocal(view) {
  const index = notes.findIndex((n) => n.id === view.id);
  if (index >= 0) notes[index] = view; else notes.push(view);
  notes.sort((a, b) => (b.isPinned - a.isPinned) || (b.timestamp - a.timestamp));
}

function enqueue(task) {
  writes = writes.then(task).catch((error) => {
    setStatus(`Not saved: ${error}`);
    showToast(`Not saved: ${error}`);
  });
  return writes;
}

/** Queues a save of what the editor shows now, for the note it shows now. */
function saveNow() {
  clearTimeout(saveTimer);
  saveTimer = null;
  const target = current;
  if (!target) return writes;
  const title = el.title.value;
  const content = el.content.value;
  return enqueue(async () => {
    if (target.id === null) {
      if (!title && !content) return; // An untouched draft is not a note.
      const created = await invoke('create_note', { title, content });
      target.id = created.id;
      upsertLocal(created);
      if (current === target) {
        el.pin.disabled = false;
        el.del.disabled = false;
      }
    } else {
      upsertLocal(await invoke('update_note', { id: target.id, title, content }));
    }
    setStatus(account?.signedIn ? 'Saved on this device.' : SAVED);
    render();
    if (account?.signedIn) {
      // The panel must not keep saying "Up to date" while this edit waits to upload.
      account = await invoke('account');
      renderAccount();
    }
    scheduleAutoSync();
  });
}

function scheduleSave() {
  lastInputAt = Date.now();
  setStatus('Saving…');
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveNow, SAVE_DELAY_MS);
}

/** Switching is immediate: the previous note's save is queued first, with that note's own text. */
function openNote(id) {
  saveNow();
  current = { id };
  fillEditor(notes.find((n) => n.id === id));
  showEditor(true);
  render();
  el.content.focus();
}

function newNote() {
  saveNow();
  current = { id: null };
  fillEditor(null);
  showEditor(true);
  render();
  el.title.focus();
}

function closeEditor() {
  saveNow();
  current = null;
  fillEditor(null);
  showEditor(false);
  render();
}

function deleteCurrent() {
  const target = current;
  if (!target) return;
  saveNow();
  enqueue(async () => {
    if (target.id === null) return; // A draft never saved has nothing to delete.
    const removed = await invoke('delete_note', { id: target.id });
    notes = notes.filter((n) => n.id !== removed.id);
    if (current === target) {
      current = null;
      fillEditor(null);
      showEditor(false);
    }
    render();
    showToast(`Deleted "${removed.title.trim() || 'Untitled'}".`, () => restore(removed));
  });
}

/** Undo brings the words back as a new note (the old id stays retired, as on the other clients). */
function restore(removed) {
  enqueue(async () => {
    const created = await invoke('create_note', { title: removed.title, content: removed.content });
    upsertLocal(created);
    openNote(created.id);
  });
}

function togglePin() {
  const target = current;
  saveNow();
  enqueue(async () => {
    const note = target && notes.find((n) => n.id === target.id);
    if (!note) return;
    const saved = await invoke('set_pinned', { id: note.id, pinned: !note.isPinned });
    upsertLocal(saved);
    if (current === target) setPinButton(saved.isPinned);
    render();
  });
}

el.newNote.addEventListener('click', newNote);
el.search.addEventListener('input', render);
el.title.addEventListener('input', scheduleSave);
el.content.addEventListener('input', scheduleSave);
el.title.addEventListener('blur', () => { if (saveTimer) saveNow(); });
el.content.addEventListener('blur', () => { if (saveTimer) saveNow(); });
el.title.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); el.content.focus(); }
});
el.pin.addEventListener('click', togglePin);
el.del.addEventListener('click', deleteCurrent);
el.back.addEventListener('click', () => { closeEditor(); el.search.focus(); });
el.form.addEventListener('submit', (e) => e.preventDefault());

document.addEventListener('keydown', (e) => {
  const key = e.key.toLowerCase();
  if (e.ctrlKey && !e.shiftKey && key === 'n') { e.preventDefault(); newNote(); }
  else if (e.ctrlKey && key === 'f') { e.preventDefault(); el.search.focus(); el.search.select(); }
  else if (e.ctrlKey && key === 's') { e.preventDefault(); saveNow(); }
  else if (e.key === 'Escape' && !el.toast.hidden) hideToast();
});

// The window waits for the last autosave before it closes (beforeunload cannot wait for an async save).
window.__TAURI__.window.getCurrentWindow().onCloseRequested(async () => {
  await saveNow();
});

async function start() {
  try {
    account = await invoke('account');
    renderAccount();
    notes = await invoke('list_notes');
    const info = await invoke('app_info');
    el.status.title = `Encrypted on this device: ${info.dataFile}`;
    showEditor(false);
    render();
    if (notes.length === 0) el.newNote.focus(); else el.search.focus();
  } catch (error) {
    setStatus(`Could not load notes: ${error}`);
  } finally {
    document.body.dataset.ready = 'true'; // For the end-to-end driver.
  }
  if (account?.signedIn) syncNow({ quiet: true });
}

// --- Account and sync -----------------------------------------------------------------------------

const AUTO_SYNC_IDLE_MS = 3_000;
const AUTO_SYNC_EVERY_MS = 60_000;
let account = null;
let lastInputAt = 0;
let autoSyncTimer = null;
let syncing = false;

function timeOf(ms) {
  return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

function plural(count, one, many) {
  return count === 1 ? one : many;
}

function renderAccount() {
  if (!account) return;
  el.badge.textContent = account.signedIn ? 'Rust · synced' : 'Rust · guest';
  el.account.hidden = !account.syncAvailable;
  el.signInOpen.hidden = account.signedIn;
  el.syncButton.hidden = !account.signedIn;
  el.signOut.hidden = !account.signedIn;
  el.syncButton.disabled = syncing;
  el.accountLine.textContent = account.signedIn
    ? `Signed in as ${account.email ?? 'your account'}`
    : 'Guest: notes stay on this device.';
  let line = '';
  if (syncing) line = 'Syncing…';
  else if (account.lastError) line = account.lastError;
  else if (account.signedIn && account.waiting > 0) line = `${account.waiting} ${plural(account.waiting, 'change', 'changes')} waiting to sync`;
  else if (account.signedIn && account.lastSyncedAt) line = `Up to date · ${timeOf(account.lastSyncedAt)}`;
  el.syncLine.textContent = line;
  el.syncLine.classList.toggle('error', Boolean(account.lastError) && !syncing);
}

function scheduleAutoSync() {
  if (!account?.signedIn) return;
  clearTimeout(autoSyncTimer);
  autoSyncTimer = setTimeout(() => syncNow({ quiet: true }), AUTO_SYNC_IDLE_MS);
}

function setEditorLocked(locked) {
  el.title.readOnly = locked;
  el.content.readOnly = locked;
}

/**
 * Syncs in the write queue, after the editor's text has been saved. The editor is read-only while it
 * runs: text typed over an old copy of a note that the sync just replaced would otherwise be saved over
 * the other device's words. Automatic syncs wait for a pause in typing.
 */
function syncNow({ quiet = false } = {}) {
  if (!account?.signedIn || syncing) return writes;
  if (quiet && Date.now() - lastInputAt < AUTO_SYNC_IDLE_MS) {
    scheduleAutoSync();
    return writes;
  }
  saveNow();
  return enqueue(async () => {
    syncing = true;
    renderAccount();
    setEditorLocked(true);
    try {
      const summary = await invoke('sync_now');
      notes = await invoke('list_notes');
      refreshOpenNote();
      render();
      if (summary.conflictedCopies > 0) {
        showToast(`${summary.conflictedCopies} ${plural(summary.conflictedCopies, 'note was', 'notes were')} changed on two devices. Both versions were kept.`);
      } else if (summary.restoredByCloud > 0) {
        showToast(`${summary.restoredByCloud} deleted ${plural(summary.restoredByCloud, 'note', 'notes')} came back: changed on another device.`);
      }
    } catch (error) {
      if (!quiet) showToast(String(error));
    } finally {
      syncing = false;
      setEditorLocked(false);
      account = await invoke('account').catch(() => account);
      renderAccount();
    }
  });
}

/** After a sync: show the open note's new words, or close it if the sync removed it. */
function refreshOpenNote() {
  if (!current || current.id === null) return;
  const latest = notes.find((n) => n.id === current.id);
  if (!latest) {
    current = null;
    fillEditor(null);
    showEditor(false);
    showToast('The note you had open was deleted on another device.');
    return;
  }
  if (latest.title !== el.title.value || latest.content !== el.content.value) {
    el.title.value = latest.title;
    el.content.value = latest.content;
  }
  setPinButton(latest.isPinned);
}

/** Signing in or out shows another account's notes: close the editor and reload the list. */
async function switchedAccount(view) {
  account = view;
  current = null;
  fillEditor(null);
  showEditor(false);
  notes = await invoke('list_notes');
  render();
  renderAccount();
}

function openSignIn() {
  el.signInError.textContent = '';
  el.emailStep.hidden = false;
  el.codeStep.hidden = true;
  el.dialog.showModal();
  el.email.focus();
}

el.signInOpen.addEventListener('click', openSignIn);
el.signInCancel.addEventListener('click', () => el.dialog.close());
el.changeEmail.addEventListener('click', () => {
  el.codeStep.hidden = true;
  el.emailStep.hidden = false;
  el.signInError.textContent = '';
  el.email.focus();
});
el.emailStep.addEventListener('submit', async (e) => {
  e.preventDefault();
  el.signInError.textContent = '';
  el.sendCode.disabled = true;
  try {
    await invoke('request_code', { email: el.email.value });
    el.codeSent.textContent = `We sent a code to ${el.email.value.trim()}.`;
    el.emailStep.hidden = true;
    el.codeStep.hidden = false;
    el.code.value = '';
    el.code.focus();
  } catch (error) {
    el.signInError.textContent = String(error);
  } finally {
    el.sendCode.disabled = false;
  }
});
el.codeStep.addEventListener('submit', async (e) => {
  e.preventDefault();
  el.signInError.textContent = '';
  el.verify.disabled = true;
  try {
    await saveNow();
    const view = await invoke('verify_code', { email: el.email.value, code: el.code.value });
    el.dialog.close();
    await switchedAccount(view);
    syncNow();
  } catch (error) {
    el.signInError.textContent = String(error);
  } finally {
    el.verify.disabled = false;
  }
});
el.syncButton.addEventListener('click', () => syncNow());
el.signOut.addEventListener('click', async () => {
  await saveNow();
  const waiting = account?.waiting ?? 0;
  if (waiting > 0) {
    showToast(`${waiting} ${plural(waiting, 'change has', 'changes have')} not synced yet. They stay on this device and sync when you sign in again.`);
  }
  try {
    await switchedAccount(await invoke('sign_out'));
  } catch (error) {
    showToast(String(error));
  }
});

setInterval(() => syncNow({ quiet: true }), AUTO_SYNC_EVERY_MS);
window.addEventListener('focus', () => syncNow({ quiet: true }));

start();
