import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    fetchAllNotes: vi.fn(),
    subscribeToNotes: vi.fn(),
    syncNotesWithCloud: vi.fn(),
    upsertNote: vi.fn(),
    deleteNote: vi.fn(),
    uploadAllNotes: vi.fn(),
    pushPendingEdits: vi.fn(),
  },
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));

import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import {
  startNotesRealtimeSync,
  stopNotesRealtimeSync,
  waitForRealtimeMirrorWriteForTests,
} from '@/lib/notes/notesSyncService';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = '11111111-1111-4111-8111-111111111111';

/**
 * Reconnecting re-runs the reconcile, but that is throttled: skipped within 15 s of a realtime
 * snapshot or 30 s of the last reconcile. An edit made during a short drop then sat unpushed until
 * the tab was refocused or the note edited again — while the editor called it synced. The queued
 * edits do not wait for the throttle; only the snapshot comparison does.
 */
describe('reconnecting pushes queued edits even when the reconcile is throttled', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    await waitForRealtimeMirrorWriteForTests();
    stopNotesRealtimeSync();
    useNotesStore.getState().reset();
    await resetNotesDatabaseForTests();
    remoteMocks.pushPendingEdits.mockResolvedValue(undefined);
  });

  it('pushes the queue on the online event right after a snapshot', async () => {
    let onData!: (notes: Note[]) => void;
    remoteMocks.subscribeToNotes.mockImplementation((_user: string, data: typeof onData) => {
      onData = data;
      return () => {};
    });
    startNotesRealtimeSync(USER);
    onData([createEmptyNote({ id: '1', localId: 1, title: 'one', timestamp: 1 })]);
    await waitForRealtimeMirrorWriteForTests();

    window.dispatchEvent(new Event('online'));

    await vi.waitFor(() => expect(remoteMocks.pushPendingEdits).toHaveBeenCalledWith(USER));
    expect(remoteMocks.syncNotesWithCloud).not.toHaveBeenCalled();
    stopNotesRealtimeSync();
  });
});
