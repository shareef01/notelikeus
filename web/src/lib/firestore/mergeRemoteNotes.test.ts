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

  it('never overwrites a locked local note', async () => {
    const local = [
      note({ id: '1', localId: 1, timestamp: 10, title: 'Secret', isLocked: true }),
    ];
    const remote = [note({ id: '1', localId: 1, timestamp: 99, title: 'Stale cloud' })];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Secret');
    expect(merged[0]?.isLocked).toBe(true);
  });
});
