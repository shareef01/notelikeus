import { beforeEach, describe, expect, it, vi } from 'vitest';

const { restoreMock } = vi.hoisted(() => ({ restoreMock: vi.fn() }));

vi.mock('@/lib/notes/noteActions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/notes/noteActions')>()),
  restorePermanentlyDeletedNote: restoreMock,
}));

import { permanentDeleteMessage, reportPermanentDelete } from '@/screens/main/useNoteActions';
import { useToastStore } from '@/store/toastStore';
import { createEmptyNote } from '@/types/note';

describe('permanentDeleteMessage', () => {
  it('leaves the message alone when every delete reached the cloud', () => {
    expect(permanentDeleteMessage('Note deleted permanently', 1, 0)).toBe('Note deleted permanently');
  });

  it('says a single note stays on other devices', () => {
    expect(permanentDeleteMessage('Note deleted permanently', 1, 1)).toBe(
      'Note deleted permanently. It stays on your other devices until the next sync.',
    );
  });

  it('counts the notes that stay in a batch', () => {
    expect(permanentDeleteMessage('Deleted 3 notes permanently', 3, 1)).toBe(
      'Deleted 3 notes permanently. 1 stays on your other devices until the next sync.',
    );
    expect(permanentDeleteMessage('Deleted 3 notes permanently', 3, 2)).toBe(
      'Deleted 3 notes permanently. 2 stay on your other devices until the next sync.',
    );
  });
});

describe('permanentDeleteMessage with failures', () => {
  it('names the notes that were not deleted', () => {
    expect(permanentDeleteMessage('Deleted 2 notes permanently', 2, 0, 1)).toBe(
      "Deleted 2 notes permanently. 1 note couldn't be deleted. Try again.",
    );
  });

  it('reports cloud-pending and failed notes together', () => {
    expect(permanentDeleteMessage('Deleted 2 notes permanently', 2, 1, 2)).toBe(
      "Deleted 2 notes permanently. 1 stays on your other devices until the next sync. 2 notes couldn't be deleted. Try again.",
    );
  });
});

describe('reportPermanentDelete', () => {
  const notes = ['A', 'B', 'C'].map((id, index) =>
    createEmptyNote({ id, localId: index + 1, title: id }),
  );

  beforeEach(() => {
    restoreMock.mockReset();
    restoreMock.mockResolvedValue(undefined);
    useToastStore.getState().dismiss();
  });

  it('offers Undo for exactly the notes that were deleted', async () => {
    reportPermanentDelete(
      { removedIds: ['A', 'C'], pendingInCloud: 0, failedIds: ['B'] },
      notes,
      (deleted) => `Deleted ${deleted} notes permanently`,
    );

    const toast = useToastStore.getState().message;
    expect(toast?.text).toBe("Deleted 2 notes permanently. 1 note couldn't be deleted. Try again.");
    expect(toast?.tone).toBe('error');
    expect(toast?.actionLabel).toBe('Undo');

    toast?.onAction?.();
    await vi.waitFor(() => expect(restoreMock).toHaveBeenCalledTimes(2));
    expect(restoreMock.mock.calls.map(([note]) => note.id)).toEqual(['A', 'C']);
  });

  it('throws when nothing was deleted, so the action reports a failure', () => {
    expect(() =>
      reportPermanentDelete(
        { removedIds: [], pendingInCloud: 0, failedIds: ['A'] },
        notes,
        () => 'Note deleted permanently',
      ),
    ).toThrow(/1 note could not be deleted/);
    expect(useToastStore.getState().message).toBeNull();
  });
});
