/**
 * Measurement harness for OPUS-PERF-001. Reports timings; does not fail.
 * Run: node scripts/benchNotesContentKey.mjs
 */
function makeNote(i, contentChars) {
  const body = 'x'.repeat(contentChars);
  return {
    id: String(i),
    localId: i,
    title: `Note ${i}`,
    content: body,
    timestamp: i,
    serverUpdatedAt: i,
    position: i,
    color: 0,
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    reminderTimestamp: null,
    labels: [],
    attachments: [],
    checklist: [],
  };
}

function library(count, contentChars) {
  return Array.from({ length: count }, (_, i) => makeNote(i, contentChars));
}

function noteContentKey(note) {
  return [
    note.id,
    note.timestamp,
    note.serverUpdatedAt ?? '',
    note.position,
    note.color,
    note.isPinned ? 1 : 0,
    note.isArchived ? 1 : 0,
    note.isTrashed ? 1 : 0,
    note.reminderTimestamp ?? '',
    note.title,
    note.content,
    '',
    '',
    '',
  ].join('\u001f');
}

function oldNotesContentKey(notes) {
  return [...notes]
    .map((note) => noteContentKey(note))
    .sort()
    .join('|');
}

function oldEqual(a, b) {
  if (a.length !== b.length) return false;
  return oldNotesContentKey(a) === oldNotesContentKey(b);
}

function notesEqual(a, b) {
  if (a === b) return true;
  return (
    a.id === b.id &&
    a.timestamp === b.timestamp &&
    a.serverUpdatedAt === b.serverUpdatedAt &&
    a.position === b.position &&
    a.color === b.color &&
    a.isPinned === b.isPinned &&
    a.isArchived === b.isArchived &&
    a.isTrashed === b.isTrashed &&
    a.reminderTimestamp === b.reminderTimestamp &&
    a.title === b.title &&
    a.content === b.content &&
    a.labels.length === b.labels.length &&
    a.attachments.length === b.attachments.length &&
    a.checklist.length === b.checklist.length
  );
}

function newEqual(a, b) {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  let sameRefs = true;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      sameRefs = false;
      break;
    }
  }
  if (sameRefs) return true;
  const byId = new Map();
  for (const note of a) byId.set(note.id, note);
  for (const note of b) {
    const other = byId.get(note.id);
    if (!other || !notesEqual(note, other)) return false;
  }
  return true;
}

function time(label, runs, fn) {
  fn();
  const start = performance.now();
  for (let i = 0; i < runs; i++) fn();
  const ms = (performance.now() - start) / runs;
  return `${label}: ${ms.toFixed(3)} ms/call`;
}

const lines = [];
for (const count of [100, 1000, 5000]) {
  const notes = library(count, 2000);
  const clone = notes.slice();
  const shuffled = notes.slice().reverse();
  lines.push(time(`OLD notesContentKey n=${count} (2KB bodies)`, 10, () => oldNotesContentKey(notes)));
  lines.push(time(`OLD notesContentEqual identical n=${count}`, 10, () => oldEqual(notes, clone)));
  lines.push(time(`NEW notesContentEqual same-ref n=${count}`, 50, () => newEqual(notes, notes)));
  lines.push(time(`NEW notesContentEqual same-order n=${count}`, 20, () => newEqual(notes, clone)));
  lines.push(time(`NEW notesContentEqual reversed n=${count}`, 20, () => newEqual(notes, shuffled)));
}

console.log(lines.join('\n'));
