import { beforeEach, describe, expect, it, vi } from 'vitest';
import 'fake-indexeddb/auto';
import { NOTES_DB_NAME } from '@/lib/local/constants';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import {
  RemoteNoteDeletedError,
  RemoteTransportError,
  RevisionConflictError,
} from '@/lib/remote/remoteErrors';
import { applyNoteChange } from '@/lib/supabase/supabaseSyncEngine';
import { createEmptyNote } from '@/types/note';

const rpc = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc }),
}));

vi.mock('@/lib/attachments/attachmentSyncService', () => ({
  hydrateNotesWithAttachments: async (notes: unknown) => notes,
}));

const USER = '11111111-1111-4111-8111-111111111111';
const note = createEmptyNote({ id: '1', localId: 1, title: 'Secret title', content: 'Secret body' });

describe('applyNoteChange typed errors', () => {
  beforeEach(async () => {
    rpc.mockReset();
    await resetNotesDatabaseForTests();
    indexedDB.deleteDatabase(NOTES_DB_NAME);
    await resetNotesDatabaseForTests();
  });

  it('surfaces a revision conflict without the remote note body in the message', async () => {
    rpc.mockResolvedValue({
      data: {
        status: 'conflict',
        current: {
          note_id: '1',
          local_id: 1,
          title: 'Secret title',
          content: 'Secret body',
          revision: 9,
        },
      },
      error: null,
    });

    await expect(applyNoteChange(USER, note, 1)).rejects.toBeInstanceOf(RevisionConflictError);
    try {
      await applyNoteChange(USER, note, 1);
    } catch (error) {
      expect(String(error)).not.toContain('Secret title');
      expect(String(error)).not.toContain('Secret body');
    }
  });

  it('surfaces a transport failure instead of treating it as a conflict', async () => {
    rpc.mockResolvedValue({
      data: null,
      error: { message: 'timeout' },
    });

    await expect(applyNoteChange(USER, note, 1)).rejects.toBeInstanceOf(RemoteTransportError);
  });

  it('surfaces a remote delete', async () => {
    rpc.mockResolvedValue({
      data: { status: 'conflict', error: 'note_deleted' },
      error: null,
    });

    await expect(applyNoteChange(USER, note, 1)).rejects.toBeInstanceOf(RemoteNoteDeletedError);
  });
});
