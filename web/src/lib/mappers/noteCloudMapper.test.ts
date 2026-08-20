import { describe, expect, it } from 'vitest';
import { cloudMapToNote, type FirestoreNoteDocument } from './noteCloudMapper';

/**
 * Every field `firestore.rules` type-checks with `is int` has to arrive as a whole number.
 *
 * A fractional value in any of them is accepted by the write path and then rejected by the rules
 * on every sync attempt, so the note lives on locally and silently never replicates. Backups are
 * user-editable JSON and import runs through this mapper, so it is reachable without malice.
 */
const base = {
  localId: 17,
  title: 't',
  content: 'c',
  timestamp: 1_755_000_000_000,
  color: 0,
  isPinned: false,
  isArchived: false,
  isTrashed: false,
  position: 0,
  labels: [],
  checklist: [],
} as unknown as FirestoreNoteDocument;

const doc = (overrides: Record<string, unknown>) =>
  cloudMapToNote('note-1', { ...base, ...overrides } as FirestoreNoteDocument);

describe('cloudMapToNote integer fields', () => {
  it('truncates a fractional timestamp', () => {
    expect(doc({ timestamp: 1_755_000_000_000.5 }).timestamp).toBe(1_755_000_000_000);
  });

  it('truncates a fractional reminderTimestamp', () => {
    expect(doc({ reminderTimestamp: 1_755_000_000_000.9 }).reminderTimestamp).toBe(
      1_755_000_000_000,
    );
  });

  it('keeps a null reminderTimestamp null', () => {
    expect(doc({ reminderTimestamp: null }).reminderTimestamp).toBeNull();
  });

  it('truncates a fractional color and position', () => {
    const note = doc({ color: -14_005_650.7, position: 3.9 });
    expect(note.color).toBe(-14_005_650);
    expect(note.position).toBe(3);
  });

  it('truncates a fractional localId', () => {
    expect(doc({ localId: 42.7 }).localId).toBe(42);
  });

  it('truncates fractional checklist positions', () => {
    const note = doc({ checklist: [{ text: 'a', isChecked: false, position: 2.6 }] });
    expect(note.checklist[0].position).toBe(2);
  });

  it('does not treat a fractional document id as a localId', () => {
    // "1.5" parses as a finite number but is not a whole one, so it must not become the localId.
    const note = cloudMapToNote('1.5', { ...base, localId: 9 } as FirestoreNoteDocument);
    expect(note.localId).toBe(9);
  });

  it('still uses a whole numeric document id', () => {
    const note = cloudMapToNote('1755000000001', { ...base, localId: 9 } as FirestoreNoteDocument);
    expect(note.localId).toBe(1_755_000_000_001);
  });

  it('leaves whole values untouched', () => {
    const note = doc({ timestamp: 1_755_000_000_000, position: 4, color: -1 });
    expect(note.timestamp).toBe(1_755_000_000_000);
    expect(note.position).toBe(4);
    expect(note.color).toBe(-1);
  });
});
