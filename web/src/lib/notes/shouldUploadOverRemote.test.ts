import { describe, expect, it } from 'vitest';
import { shouldUploadOverRemote } from '@/lib/notes/remoteMerge';
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

  it('does not re-upload when both sides are the same confirmed revision', () => {
    const local = note({ id: '1', localId: 1, timestamp: 10, serverUpdatedAt: 500 });
    const remote = note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 500 });
    expect(shouldUploadOverRemote(local, remote)).toBe(false);
  });

  it('does not re-upload when stamps and client timestamps both match', () => {
    const local = note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 500 });
    const remote = note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 500 });
    expect(shouldUploadOverRemote(local, remote)).toBe(false);
  });

  it('uploads an equal-stamp local edit whose client timestamp is newer', () => {
    const local = note({ id: '1', localId: 1, timestamp: 30, serverUpdatedAt: 500 });
    const remote = note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 500 });
    expect(shouldUploadOverRemote(local, remote)).toBe(true);
  });

  it('refuses to overwrite a strictly newer remote stamp even if local timestamp is newer', () => {
    const local = note({ id: '1', localId: 1, timestamp: 999, serverUpdatedAt: 500 });
    const remote = note({ id: '1', localId: 1, timestamp: 1, serverUpdatedAt: 501 });
    expect(shouldUploadOverRemote(local, remote)).toBe(false);
  });

  it('uploads when the local copy is a strictly newer confirmed revision', () => {
    const local = note({ id: '1', localId: 1, timestamp: 10, serverUpdatedAt: 501 });
    const remote = note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 500 });
    expect(shouldUploadOverRemote(local, remote)).toBe(true);
  });

  it('treats equal legacy timestamps as safe to upload/keep local', () => {
    const local = note({ id: '1', localId: 1, timestamp: 500, serverUpdatedAt: null });
    const remote = note({ id: '1', localId: 1, timestamp: 500, serverUpdatedAt: null });
    expect(shouldUploadOverRemote(local, remote)).toBe(true);
  });

  it('refuses to let an unconfirmed local note beat a confirmed remote even when timestamps are equal', () => {
    const local = note({ id: '1', localId: 1, timestamp: 500, serverUpdatedAt: null });
    const remote = note({ id: '1', localId: 1, timestamp: 500, serverUpdatedAt: 500 });
    expect(shouldUploadOverRemote(local, remote)).toBe(false);
  });
});
