import { describe, expect, it } from 'vitest';
import { mergeRemoteNotes } from '@/lib/notes/remoteMerge';
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
    const local = [
      note({ id: '1', localId: 1, timestamp: 999_999, serverUpdatedAt: 10, title: 'Clock-skewed local' }),
    ];
    const remote = [
      note({ id: '1', localId: 1, timestamp: 20, serverUpdatedAt: 30, title: 'Server-confirmed remote' }),
    ];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Server-confirmed remote');
  });

  it('lets confirmed remote beat unconfirmed local regardless of client timestamp', async () => {
    const local = [
      note({ id: '1', localId: 1, timestamp: 99, serverUpdatedAt: null, title: 'Imported local' }),
    ];
    const remote = [
      note({ id: '1', localId: 1, timestamp: 10, serverUpdatedAt: 500, title: 'Confirmed remote' }),
    ];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Confirmed remote');
  });

  it('takes remote when both notes are the same confirmed revision', async () => {
    const local = [
      note({ id: '1', localId: 1, timestamp: 10, serverUpdatedAt: 500, title: 'Local same revision' }),
    ];
    const remote = [
      note({ id: '1', localId: 1, timestamp: 999, serverUpdatedAt: 500, title: 'Remote same revision' }),
    ];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Remote same revision');
  });

  it('keeps local when both legacy notes have equal timestamps', async () => {
    const local = [
      note({ id: '1', localId: 1, timestamp: 500, serverUpdatedAt: null, title: 'Local tie' }),
    ];
    const remote = [
      note({ id: '1', localId: 1, timestamp: 500, serverUpdatedAt: null, title: 'Remote tie' }),
    ];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged[0]?.title).toBe('Local tie');
  });

  it('handles mixed collections without dropping unique local notes while replacing only stale ones', async () => {
    const local = [
      note({ id: '1', localId: 1, timestamp: 100, serverUpdatedAt: null, title: 'Stale local' }),
      note({ id: '2', localId: 2, timestamp: 300, serverUpdatedAt: 800, title: 'Keep local' }),
    ];
    const remote = [
      note({ id: '1', localId: 1, timestamp: 50, serverUpdatedAt: 900, title: 'Confirmed remote' }),
      note({ id: '3', localId: 3, timestamp: 10, title: 'Remote only' }),
    ];
    const merged = await mergeRemoteNotes(local, remote);
    expect(merged).toHaveLength(3);
    expect(merged.find((entry) => entry.id === '1')?.title).toBe('Confirmed remote');
    expect(merged.find((entry) => entry.id === '2')?.title).toBe('Keep local');
    expect(merged.find((entry) => entry.id === '3')?.title).toBe('Remote only');
  });
});
