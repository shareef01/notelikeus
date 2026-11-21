// UI for the Rust experiment. Every note text is written with textContent or .value, never as HTML.
'use strict';

const invoke = window.__TAURI__.core.invoke;
const $ = (id) => document.getElementById(id);

const el = {
  app: $('app'), list: $('note-list'), emptyList: $('empty-list'), noMatches: $('no-matches'),
  search: $('search'), newNote: $('new-note'), form: $('editor-form'), editorEmpty: $('editor-empty'),
  title: $('title'), content: $('content'), pin: $('pin'), del: $('delete'), back: $('back'),
  status: $('status'), toast: $('toast'), toastText: $('toast-text'), toastAction: $('toast-action'),
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
    setStatus(SAVED);
    render();
  });
}

function scheduleSave() {
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
}

start();
