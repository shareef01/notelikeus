import { describe, expect, it } from 'vitest';
import { mergeRemoteNotes } from '@/lib/firestore/notesRepository';
import { createEmptyNote, type Note } from '@/types/note';

function note(partial: Partial<Note> & Pick<Note, 'id' | 'localId' | 'timestamp'>): Note {
  return createEmptyNote(partial);
}

describe('mergeRemoteNotes', () => {
  it('adds remote-only notes', async () => {
    const remote = [note({ id: '1', localId: 1, timestamp: 10, title: 'Remote' })];
    const merged = await mergeRemoteNotes([], remote);
    expect(merged).toHaveLength(1);
    expect(merged[0]?.title).toBe('Remote');
  });

  it('keeps newer local timestamp (LWW)', async () => {
    const local = [note({ id: '1', localId: 1, timestamp: 20, title: 'Local' })];
    const remote = [note({ id: '1', localId: 1, timestamp: 10, title: 'Remote' })];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Local');
  });

  it('takes remote when remote timestamp is newer', async () => {
    const local = [note({ id: '1', localId: 1, timestamp: 10, title: 'Local' })];
    const remote = [note({ id: '1', localId: 1, timestamp: 20, title: 'Remote' })];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Remote');
  });

  it('keeps the local note when it is strictly newer than the remote copy', async () => {
    const local = [note({ id: '1', localId: 1, timestamp: 99, title: 'Local wins' })];
    const remote = [note({ id: '1', localId: 1, timestamp: 10, title: 'Stale cloud' })];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Local wins');
  });

  it('prefers serverUpdatedAt over a skewed client timestamp when both sides have one', async () => {
    // Local's device clock reads far in the future, but the server-confirmed order says remote
    // is actually the more recent write — serverUpdatedAt must win the tie, not `timestamp`.
    const local = [
      note({ id: '1', localId: 1, timestamp: 999_999, serverUpdatedAt: 10, title: 'Clock-skewed local' }),
    ];
    const remote = [
      note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 30, title: 'Server-confirmed remote' }),
    ];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Server-confirmed remote');
  });

  it('falls back to timestamp when either side has no serverUpdatedAt yet', async () => {
    const local = [
      note({ id: '1', localId: 1, timestamp: 99, serverUpdatedAt: null, title: 'Local wins' }),
    ];
    const remote = [
      note({ id: '1', localId: 1, timestamp: 10, serverUpdatedAt: 500, title: 'Stale cloud' }),
    ];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Local wins');
  });
});
