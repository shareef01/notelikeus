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

/** uid of the Firebase session signed in right now, or null when there is none. */
export function activeFirebaseUid(): string | null {
  if (!isFirebaseConfigured()) return null;
  try {
    initFirebase();
    return getAuth().currentUser?.uid ?? null;
  } catch {
    // Firebase unavailable in this build/session.
    return null;
  }
}

/**
 * How a candidate Firebase uid was obtained. Only `firebase-session` is evidence that *this*
 * user owns the uid; the rest are device-local breadcrumbs that survive a sign-out and therefore
 * describe whoever used this browser profile last, not whoever is signed in now.
 */
type UidSource = 'firebase-session' | 'server-mapping' | 'local-link' | 'device-breadcrumb';

interface FirebaseUidCandidate {
  firebaseUid: string;
  source: UidSource;
}

async function resolveFirebaseUid(supabaseUid: string): Promise<FirebaseUidCandidate | null> {
  // A live Firebase session is the only self-proving source, so it is checked first: when the
  // user is still signed in to Firebase, that uid wins over any stale breadcrumb.
  const sessionUid = activeFirebaseUid();
  if (sessionUid && sessionUid !== supabaseUid) {
    return { firebaseUid: sessionUid, source: 'firebase-session' };
  }

  const localLink = loadLocalFirebaseSupabaseLink();
  if (localLink?.supabaseUid === supabaseUid) {
    return { firebaseUid: localLink.firebaseUid, source: 'local-link' };
  }

  try {
    const fromServer = await fetchLinkedFirebaseUidFromServer();
    // Already linked to *this* Supabase account server-side — the claim was made previously.
    if (fromServer) return { firebaseUid: fromServer, source: 'server-mapping' };
  } catch (error) {
    console.warn('[Notelikeus] Could not read linked Firebase uid from Supabase:', error);
  }

  const lastMerged = loadLastMergedUserId();
  if (lastMerged && isLikelyFirebaseUid(lastMerged) && lastMerged !== supabaseUid) {
    return { firebaseUid: lastMerged, source: 'device-breadcrumb' };
  }

  const known = loadKnownFirebaseUid();
  if (known && known !== supabaseUid) {
    return { firebaseUid: known, source: 'device-breadcrumb' };
  }

  return null;
}

/**
 * Whether this Supabase account may *claim* a Firebase uid — i.e. write the
 * `firebase_uid_mappings` row and adopt that uid's local IndexedDB namespace.
 *
 * The invariant is: a Supabase account may claim Firebase uid F only when the app can prove the
 * same user owns authenticated Firebase identity F. `link_firebase_uid` accepts whatever uid the
 * client sends, so this is the only place the proof exists.
 *
 * Device breadcrumbs (`lastMergedUserId`, the remembered Firebase uid) are *not* proof: they
 * outlive sign-out, so on a shared browser profile they describe the previous user. Claiming on
 * that basis links account B to user A's Firebase identity, adopts A's local notes, and — because
 * `firebase_uid` is the mapping table's primary key — permanently locks A out of linking their
 * own uid.
 *
 * A mapping this account already holds (`local-link` / `server-mapping`) is not a new claim; it
 * is the claim that was already made and is safe to keep honouring offline.
 */
export function mayClaimFirebaseUid(candidate: FirebaseUidCandidate): boolean {
  switch (candidate.source) {
    case 'firebase-session':
    case 'server-mapping':
    case 'local-link':
      return true;
    case 'device-breadcrumb':
      // Only usable once corroborated by a live Firebase session for the same uid.
      return activeFirebaseUid() === candidate.firebaseUid;
  }
}

/**
 * Phase 6 migration gate for Supabase backend: link Firebase uid, migrate IndexedDB namespace,
 * and optionally import Firebase cloud data when Supabase is empty.
 */
export async function ensureFirebaseSupabaseMigration(supabaseUid: string): Promise<void> {
  if (!isSupabaseBackendEnabled() || !isSupabaseUuid(supabaseUid)) return;

  const meta = await getOwnerMeta(supabaseUid);
  if (meta?.firebaseNamespaceMigrated && meta.firebaseCloudImported) return;

  const candidate = await resolveFirebaseUid(supabaseUid);
  if (!candidate) return;
  if (!mayClaimFirebaseUid(candidate)) {
    console.warn(
      '[Notelikeus] Skipping Firebase→Supabase link: no authenticated Firebase session proves ' +
        'ownership of the candidate uid on this device.',
    );
    return;
  }

  const firebaseUid = candidate.firebaseUid;
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
      if (empty && activeFirebaseUid() === firebaseUid) {
        await importFirebaseCloudToSupabase(firebaseUid, supabaseUid);
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
