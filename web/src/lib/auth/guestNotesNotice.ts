import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { listNotes } from '@/lib/local/notesLocalRepository';
import { useToastStore } from '@/store/toastStore';

/**
 * Tells the user their guest notes stayed behind when they signed in.
 *
 * Guest notes live in their own IndexedDB namespace and are deliberately not merged into an
 * account — see `docs/BACKEND_ARCHITECTURE.md`. Signing in switches namespaces, so from the
 * user's side a library they had been writing simply disappears. The data is intact, but nothing
 * said so, which is indistinguishable from having lost it.
 *
 * This only reports; it never moves anything. Importing guest notes into an account needs
 * conflict, duplicate and attachment semantics that are a product decision, not an inference.
 *
 * Returns how many guest notes remain, for tests.
 */
export async function notifyGuestNotesRemain(): Promise<number> {
  try {
    const guestNotes = await listNotes(GUEST_OWNER_ID);
    if (guestNotes.length === 0) return 0;

    const plural = guestNotes.length === 1;
    useToastStore
      .getState()
      .show(
        `Your ${guestNotes.length} guest note${plural ? '' : 's'} ${plural ? 'is' : 'are'} still ` +
          'on this device. Guest notes are not moved into your account.',
      );
    return guestNotes.length;
  } catch {
    // Never let a storage read failure interfere with signing in.
    return 0;
  }
}
