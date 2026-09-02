import { getAuth } from 'firebase/auth';
import { initFirebase } from '@/lib/firebase';
import { isFirebaseConfigured } from '@/lib/config';
import { getOwnerMeta, setOwnerMeta } from '@/lib/local/notesLocalRepository';
import {
  accountsMatch,
  isLikelyFirebaseUid,
  isSupabaseUuid,
  loadKnownFirebaseUid,
  loadLocalFirebaseSupabaseLink,
  rememberKnownFirebaseUid,
  saveLocalFirebaseSupabaseLink,
} from '@/lib/migration/accountIdentity';
import { importFirebaseCloudToSupabase, supabaseCloudIsEmpty } from '@/lib/migration/firebaseCloudImport';
import { migrateOwnerNamespace } from '@/lib/migration/ownerNamespaceMigration';
import {
  fetchLinkedFirebaseUidFromServer,
  linkFirebaseUidOnServer,
} from '@/lib/migration/supabaseUidLink';
import { loadLastMergedUserId, saveLastMergedUserId } from '@/lib/notes/lastMergedUser';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';

async function resolveFirebaseUid(supabaseUid: string): Promise<string | null> {
  const localLink = loadLocalFirebaseSupabaseLink();
  if (localLink?.supabaseUid === supabaseUid) {
    return localLink.firebaseUid;
  }

  try {
    const fromServer = await fetchLinkedFirebaseUidFromServer();
    if (fromServer) return fromServer;
  } catch (error) {
    console.warn('[Notelikeus] Could not read linked Firebase uid from Supabase:', error);
  }

  const lastMerged = loadLastMergedUserId();
  if (lastMerged && isLikelyFirebaseUid(lastMerged) && lastMerged !== supabaseUid) {
    return lastMerged;
  }

  const known = loadKnownFirebaseUid();
  if (known && known !== supabaseUid) {
    return known;
  }

  if (isFirebaseConfigured()) {
    try {
      initFirebase();
      const firebaseUser = getAuth().currentUser;
      if (firebaseUser && firebaseUser.uid !== supabaseUid) {
        return firebaseUser.uid;
      }
    } catch {
      // Firebase unavailable in this build/session.
    }
  }

  return null;
}

/**
 * Phase 6 migration gate for Supabase backend: link Firebase uid, migrate IndexedDB namespace,
 * and optionally import Firebase cloud data when Supabase is empty.
 */
export async function ensureFirebaseSupabaseMigration(supabaseUid: string): Promise<void> {
  if (!isSupabaseBackendEnabled() || !isSupabaseUuid(supabaseUid)) return;

  const meta = await getOwnerMeta(supabaseUid);
  if (meta?.firebaseNamespaceMigrated && meta.firebaseCloudImported) return;

  const firebaseUid = await resolveFirebaseUid(supabaseUid);
  if (!firebaseUid) return;

  const lastMerged = loadLastMergedUserId();
  if (!accountsMatch(lastMerged, supabaseUid, firebaseUid)) {
    return;
  }

  saveLocalFirebaseSupabaseLink({
    firebaseUid,
    supabaseUid,
    linkedAt: Date.now(),
  });

  try {
    await linkFirebaseUidOnServer(firebaseUid);
  } catch (error) {
    console.warn('[Notelikeus] Supabase link_firebase_uid failed:', error);
  }

  if (!meta?.firebaseNamespaceMigrated) {
    await migrateOwnerNamespace(firebaseUid, supabaseUid);
    await setOwnerMeta(supabaseUid, {
      firebaseNamespaceMigrated: true,
      migratedFromOwnerId: firebaseUid,
      migratedAt: Date.now(),
    });
  }

  if (!meta?.firebaseCloudImported) {
    try {
      const empty = await supabaseCloudIsEmpty();
      if (empty && isFirebaseConfigured()) {
        initFirebase();
        if (getAuth().currentUser?.uid === firebaseUid) {
          await importFirebaseCloudToSupabase(firebaseUid, supabaseUid);
        }
      }
      await setOwnerMeta(supabaseUid, {
        firebaseCloudImported: true,
        firebaseCloudImportedAt: Date.now(),
      });
    } catch (error) {
      console.warn('[Notelikeus] Firebase → Supabase cloud import failed:', error);
    }
  }

  saveLastMergedUserId(supabaseUid);
  rememberKnownFirebaseUid(firebaseUid);
}

export { rememberKnownFirebaseUid };
