import assert from 'node:assert/strict';
import test from 'node:test';
import {
  BACKUP_VERSION,
  buildBackupPayload,
  firestoreDocToBackupNote,
} from './firestoreToBackup.mjs';

test('maps Firestore note docs into backup DTOs', () => {
  const note = firestoreDocToBackupNote('42', {
    title: 'Hello',
    content: 'Body',
    timestamp: 1000,
    color: -14474606,
    isPinned: true,
    labels: [{ name: 'Work' }],
    checklist: [{ text: 'one', isChecked: false, position: 0 }],
    reminderTimestamp: 2000,
    serverUpdatedAt: { seconds: 1, nanoseconds: 500_000_000 },
  });
  assert.equal(note.id, 42);
  assert.equal(note.title, 'Hello');
  assert.equal(note.labels[0], 'Work');
  assert.equal(note.checklist[0].text, 'one');
  assert.equal(note.reminderTimestamp, 2000);
  assert.equal(note.serverUpdatedAt, 1500);
});

test('builds importable backup JSON', () => {
  const notes = [firestoreDocToBackupNote('1', { title: 'A', content: '', timestamp: 1, color: 0 })];
  const payload = buildBackupPayload('firebase-uid', notes, 99);
  assert.equal(payload.version, BACKUP_VERSION);
  assert.equal(payload.sourceUid, 'firebase-uid');
  assert.equal(payload.exportedAt, 99);
  assert.equal(payload.notes.length, 1);
});
