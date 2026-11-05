import {
  endDeletedAccountSessionSupabase,
  sendEmailSignInCodeSupabase,
  signOutSupabase,
  verifyEmailSignInCodeSupabase,
} from '@/lib/auth/supabaseAuth';
import { clearOwner } from '@/lib/local/notesLocalRepository';
import { clearPendingAttachmentsForOwner } from '@/lib/local/pendingAttachmentRepository';
import { deleteAllSupabaseCloudData } from '@/lib/supabase/deleteAllUserCloudData';
import { requestAccountDeletion } from '@/lib/supabase/deleteAccount';
import { useAuthStore } from '@/store/authStore';

/**
 * Step 1 of email sign-in: emails a one-time code (creating the account on first use).
 * [captchaToken] is the Turnstile token the hosted project requires; builds without CAPTCHA omit it.
 */
export async function sendEmailSignInCode(email: string, captchaToken?: string): Promise<void> {
  await sendEmailSignInCodeSupabase(email, captchaToken);
}

/** Step 2 of email sign-in: exchanges the typed code for a session. */
export async function verifyEmailSignInCode(email: string, code: string): Promise<void> {
  await verifyEmailSignInCodeSupabase(email, code);
}

/**
 * Signs out. With [options.deleteCloudData], first deletes the account's cloud data and then this
 * browser's copy of it.
 *
 * The local copy has to go with the cloud one: its notes, pending edits and pending attachments are
 * exactly what the next sign-in pushes, so keeping them uploaded everything straight back into the
 * cloud the user had just emptied. Android and Windows already wipe local account data on sign-out.
 * The local wipe runs only after the cloud wipe succeeded (a failed wipe throws before it), and
 * before sign-out, while the account id is still known.
 *
 * An ordinary sign-out keeps the local copy, so offline edits upload at the next sign-in.
 */
export async function signOutAccount(options: { deleteCloudData?: boolean } = {}): Promise<void> {
  if (options.deleteCloudData) {
    const ownerId = useAuthStore.getState().user?.uid;
    await deleteAllSupabaseCloudData();
    if (ownerId) {
      await clearOwner(ownerId);
      await clearPendingAttachmentsForOwner(ownerId);
    }
  }
  await signOutSupabase();
}

/**
 * Removes this browser's copy of an account that has been deleted, and ends its session.
 *
 * Only ever called on a definite answer from the server. Pending edits go with the notes: they are
 * what the next sign-in would otherwise try to push, and the account they belong to is gone.
 */
export async function clearDeletedAccount(ownerId: string | undefined): Promise<void> {
  if (ownerId) {
    await clearOwner(ownerId);
    await clearPendingAttachmentsForOwner(ownerId);
  }
  if (ownerId && useAuthStore.getState().user?.uid === ownerId) {
    await endDeletedAccountSessionSupabase(ownerId);
  }
}

/**
 * Deletes the signed-in account for good, everywhere, then this browser's copy of it.
 *
 * Throws before touching anything local if the server did not accept the request.
 */
export async function deleteAccount(ownerId = useAuthStore.getState().user?.uid): Promise<void> {
  if (!ownerId) throw new Error('Sign in again to delete your account.');
  const assertOwner = () => {
    if (useAuthStore.getState().user?.uid !== ownerId) throw new Error('Account changed; confirm deletion again for the account you intend to delete.');
  };
  assertOwner();
  try {
    await requestAccountDeletion(ownerId);
  } catch (error) {
    // A late reauthentication refusal must not turn into a deletion prompt for another account.
    assertOwner();
    throw error;
  }
  await clearDeletedAccount(ownerId);
}
