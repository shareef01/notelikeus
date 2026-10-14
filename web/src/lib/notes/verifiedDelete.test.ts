import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const deleteNote = vi.fn(async (_userId: string, _noteId: string, _base?: number) => {});
vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => ({ deleteNote, upsertNote: vi.fn(async () => {}) }),
}));

import { authoredContentDigest } from '@/lib/notes/conflictResolution';
import { removeNote } from '@/lib/notes/noteActions';
import { partitionTombstoned } from '@/lib/notes/notesSyncService';
import { emptyRevisionState } from '@/lib/supabase/revisionStore';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

function note(content = 'milk'): Note {
  return createEmptyNote({ id: '1', localId: 1, title: 'Groceries', content, serverUpdatedAt: 1000 });
}

/**
 * AU-07. A delete made before this device knew the note's revision cannot be sent: the server
 * only deletes a known revision. It used to wait for nothing — the next snapshot let the cloud copy
 * win and the note came back without a word. Now the delete records a fingerprint of the words it
 * removed, and a snapshot that still holds exactly those words completes the delete at the
 * revision it observed; one that holds anything else wins, and the user is told.
 */
describe('a delete made without a known revision', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    useToastStore.getState().dismiss();
    useNotesStore.getState().reset();
    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });
  });

  it('records a fingerprint of the deleted words, not the words', async () => {
    useNotesStore.getState().setNotes([note()]);

    await removeNote('1');

    const state = useTombstoneStore.getState();
    expect(state.isDeleted('1')).toBe(true);
    expect(state.baselineRevisionById['1']).toBeUndefined();
    expect(state.deletedDigestById['1']).toBe(authoredContentDigest(note()));
    expect(JSON.stringify(state.deletedDigestById)).not.toContain('milk');
    // Nothing can be sent without a revision.
    expect(deleteNote).toHaveBeenCalledWith(USER, '1', undefined);
  });

  it('completes the delete against a cloud copy that still holds exactly those words', () => {
    useTombstoneStore.getState().markDeleted('1', null, 50, authoredContentDigest(note()));

    const { live, staleIds } = partitionTombstoned([note()], {
      ...emptyRevisionState,
      noteRevisions: { '1': 7 },
    });

    expect(live).toEqual([]);
    expect(staleIds).toEqual(['1']);
    expect(useTombstoneStore.getState().baselineRevisionById['1']).toBe(7);
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(true);
  });

  it('lets a changed cloud copy win, and says so', () => {
    useTombstoneStore.getState().markDeleted('1', null, 50, authoredContentDigest(note()));

    const { live, staleIds } = partitionTombstoned([note('milk, eggs (from the phone)')], {
      ...emptyRevisionState,
      noteRevisions: { '1': 7 },
    });

    expect(live.map((n) => n.content)).toEqual(['milk, eggs (from the phone)']);
    expect(staleIds).toEqual([]);
    expect(useTombstoneStore.getState().isDeleted('1')).toBe(false);
    expect(useTombstoneStore.getState().deletedDigestById['1']).toBeUndefined();
    expect(JSON.stringify(useToastStore.getState())).toContain("so it's back");
  });

  it('says nothing about a note this device never deleted', () => {
    // A tombstone merged from the cloud: no baseline, no fingerprint.
    useTombstoneStore.getState().mergeFromCloud({ '1': 50 });

    partitionTombstoned([note()], { ...emptyRevisionState, noteRevisions: { '1': 7 } });

    expect(JSON.stringify(useToastStore.getState())).not.toContain("so it's back");
  });

  it('keeps the fingerprint across a reload and drops it with the tombstone', () => {
    useTombstoneStore.getState().markDeleted('1', null, 50, 'abc');
    const persisted = JSON.parse(JSON.stringify(useTombstoneStore.getState()));
    useTombstoneStore.getState().reset();
    useTombstoneStore.setState(
      useTombstoneStore.persist.getOptions().merge!(persisted, useTombstoneStore.getState()) as never,
    );
    expect(useTombstoneStore.getState().deletedDigestById['1']).toBe('abc');

    useTombstoneStore.getState().clearIds(['1']);
    expect(useTombstoneStore.getState().deletedDigestById).toEqual({});
  });

  it('never records a fingerprint when a baseline revision is known', () => {
    useTombstoneStore.getState().markDeleted('1', 5, 50, 'abc');
    expect(useTombstoneStore.getState().deletedDigestById).toEqual({});
  });
});
