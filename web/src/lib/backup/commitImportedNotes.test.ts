import { describe, expect, it, vi, beforeEach } from 'vitest';

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    uploadAllNotes: vi.fn().mockResolvedValue(1),
  },
}));

vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));

vi.mock('@/lib/notes/notesSyncService', () => ({
  pauseRealtimeSnapshots: vi.fn(),
  resumeRealtimeSnapshots: vi.fn(),
}));

vi.mock('@/lib/attachments/attachmentSyncService', () => ({
  syncNoteAttachments: vi.fn(async (note: unknown) => note),
}));

import {
  pauseRealtimeSnapshots,
  resumeRealtimeSnapshots,
} from '@/lib/notes/notesSyncService';
import { commitImportedNotes } from '@/lib/backup/commitImportedNotes';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

function note(id: string) {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}` });
}

describe('commitImportedNotes', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
  });

  it('uploads before writing the store when signed in', async () => {
    const order: string[] = [];
    vi.mocked(pauseRealtimeSnapshots).mockImplementation(() => order.push('pause'));
    vi.mocked(remoteMocks.uploadAllNotes).mockImplementation(async () => {
      order.push('upload');
      expect(useNotesStore.getState().notes).toEqual([]);
      return 1;
    });
    vi.mocked(resumeRealtimeSnapshots).mockImplementation(() => order.push('resume'));

    const merged = [note('1')];
    const uploaded = await commitImportedNotes(merged, 1, 'user-1');

    expect(uploaded).toBe(true);
    expect(remoteMocks.uploadAllNotes).toHaveBeenCalledWith('user-1', merged);
    expect(useNotesStore.getState().notes).toEqual(merged);
    expect(order).toEqual(['pause', 'upload', 'resume']);
  });

  it('does not write the store if the upload fails', async () => {
    vi.mocked(remoteMocks.uploadAllNotes).mockRejectedValueOnce(new Error('offline'));

    await expect(commitImportedNotes([note('1')], 1, 'user-1')).rejects.toThrow('offline');

    expect(useNotesStore.getState().notes).toEqual([]);
    expect(resumeRealtimeSnapshots).toHaveBeenCalled();
  });

  it('writes locally only when signed out', async () => {
    const merged = [note('1')];
    const uploaded = await commitImportedNotes(merged, 1, undefined);

    expect(uploaded).toBe(false);
    expect(remoteMocks.uploadAllNotes).not.toHaveBeenCalled();
    expect(useNotesStore.getState().notes).toEqual(merged);
  });
});
