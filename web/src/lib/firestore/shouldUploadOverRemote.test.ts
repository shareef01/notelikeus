import { describe, expect, it } from 'vitest';
import { shouldUploadOverRemote } from '@/lib/firestore/notesRepository';
import { createEmptyNote, type Note } from '@/types/note';

function note(partial: Partial<Note> & Pick<Note, 'id' | 'localId'>): Note {
  return createEmptyNote(partial);
}

describe('shouldUploadOverRemote', () => {
  it('always uploads when there is no remote copy', () => {
    const local = note({ id: '1', localId: 1, timestamp: 1, serverUpdatedAt: null });
    expect(shouldUploadOverRemote(local, undefined)).toBe(true);
  });

  it('prefers serverUpdatedAt over a skewed client timestamp when both sides have one', () => {
    const local = note({ id: '1', localId: 1, timestamp: 999_999, serverUpdatedAt: 10 });
    const remote = note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 30 });
    expect(shouldUploadOverRemote(local, remote)).toBe(false);
  });

  it('falls back to timestamp when neither side has a serverUpdatedAt yet', () => {
    const local = note({ id: '1', localId: 1, timestamp: 99, serverUpdatedAt: null });
    const remote = note({ id: '1', localId: 1, timestamp: 10, serverUpdatedAt: null });
    expect(shouldUploadOverRemote(local, remote)).toBe(true);
  });

  it('refuses to overwrite a confirmed-synced remote with an untrusted local timestamp', () => {
    // Simulates an imported backup file: cloudMapToNote never resolves a plain JSON value into
    // a real serverUpdatedAt, so an imported note's serverUpdatedAt is always null even if its
    // `timestamp` field was hand-edited to look far newer than the real remote copy.
    const importedFromBackup = note({
      id: '1',
      localId: 1,
      timestamp: 999_999_999,
      serverUpdatedAt: null,
    });
    const confirmedRemote = note({ id: '1', localId: 1, timestamp: 100, serverUpdatedAt: 500 });
    expect(shouldUploadOverRemote(importedFromBackup, confirmedRemote)).toBe(false);
  });

  it('uploads when local is confirmed-synced but remote predates the field', () => {
    const local = note({ id: '1', localId: 1, timestamp: 5, serverUpdatedAt: 500 });
    const legacyRemote = note({ id: '1', localId: 1, timestamp: 999_999, serverUpdatedAt: null });
    expect(shouldUploadOverRemote(local, legacyRemote)).toBe(true);
  });
});
