import { beforeEach, describe, expect, it, vi } from 'vitest';
import { notifyGuestNotesRemain } from '@/lib/auth/guestNotesNotice';
import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { putNote, clearOwner } from '@/lib/local/notesLocalRepository';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { useToastStore } from '@/store/toastStore';
import { createEmptyNote } from '@/types/note';

/**
 * Guest notes are deliberately not merged into an account, so signing in makes a library the
 * user had been writing disappear from the UI while staying on disk. The data is intact; nothing
 * said so, which from the user's side is indistinguishable from losing it.
 */
describe('guest notes notice', () => {
  beforeEach(async () => {
    await resetNotesDatabaseForTests();
    useToastStore.getState().dismiss();
  });

  it('says how many guest notes stayed behind', async () => {
    await putNote(GUEST_OWNER_ID, createEmptyNote({ id: '1', localId: 1 }));
    await putNote(GUEST_OWNER_ID, createEmptyNote({ id: '2', localId: 2 }));

    expect(await notifyGuestNotesRemain()).toBe(2);
    expect(useToastStore.getState().message?.text).toContain('2 guest notes');
  });

  it('uses singular wording for one note', async () => {
    await putNote(GUEST_OWNER_ID, createEmptyNote({ id: '1', localId: 1 }));

    await notifyGuestNotesRemain();

    expect(useToastStore.getState().message?.text).toContain('1 guest note is');
  });

  it('says nothing when there were no guest notes', async () => {
    expect(await notifyGuestNotesRemain()).toBe(0);
    expect(useToastStore.getState().message).toBeNull();
  });

  it('never moves the guest notes into the account', async () => {
    await putNote(GUEST_OWNER_ID, createEmptyNote({ id: '1', localId: 1 }));

    await notifyGuestNotesRemain();

    // Reporting only. Importing needs conflict and attachment semantics that are a product
    // decision, and silently merging one namespace into another would be worse than the gap.
    const { listNotes } = await import('@/lib/local/notesLocalRepository');
    expect(await listNotes(GUEST_OWNER_ID)).toHaveLength(1);
    expect(await listNotes('some-account-uid')).toHaveLength(0);
  });

  it('does not break sign-in when local storage cannot be read', async () => {
    const repo = await import('@/lib/local/notesLocalRepository');
    const spy = vi.spyOn(repo, 'listNotes').mockRejectedValueOnce(new Error('storage gone'));

    await expect(notifyGuestNotesRemain()).resolves.toBe(0);
    spy.mockRestore();
  });

  it('reports nothing once the guest namespace has been cleared', async () => {
    await putNote(GUEST_OWNER_ID, createEmptyNote({ id: '1', localId: 1 }));
    await clearOwner(GUEST_OWNER_ID);

    expect(await notifyGuestNotesRemain()).toBe(0);
  });
});
