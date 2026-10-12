import { signInWithGoogleSupabase, signOutSupabase } from '@/lib/auth/supabaseAuth';
import { clearOwner } from '@/lib/local/notesLocalRepository';
import { clearPendingAttachmentsForOwner } from '@/lib/local/pendingAttachmentRepository';
import { deleteAllSupabaseCloudData } from '@/lib/supabase/deleteAllUserCloudData';
import { useAuthStore } from '@/store/authStore';

export async function signInWithGoogle(): Promise<void> {
  await signInWithGoogleSupabase();
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
export async function signOutGoogle(options: { deleteCloudData?: boolean } = {}): Promise<void> {
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
