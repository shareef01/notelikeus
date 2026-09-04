import { loadAttachmentsWorkerUrl } from '@/lib/attachments/attachmentConfig';
import { getSupabaseClient } from '@/lib/supabase/client';

export async function linkFirebaseUidOnServer(firebaseUid: string): Promise<void> {
  const { error } = await getSupabaseClient().rpc('link_firebase_uid', {
    p_firebase_uid: firebaseUid,
  });
  if (error) throw error;
}

export async function fetchLinkedFirebaseUidFromServer(): Promise<string | null> {
  const { data, error } = await getSupabaseClient().rpc('get_linked_firebase_uid');
  if (error) throw error;
  return typeof data === 'string' && data.length > 0 ? data : null;
}

export interface FirebaseUidLink {
  firebaseUid: string;
  verified: boolean;
}

/** The caller's own mapping and whether it was ever proven, or null when there is none. */
export async function fetchFirebaseUidLink(): Promise<FirebaseUidLink | null> {
  const { data, error } = await getSupabaseClient().rpc('get_firebase_uid_link');
  if (error) throw error;
  if (!data || typeof data !== 'object') return null;
  const row = data as { firebase_uid?: unknown; verified?: unknown };
  if (typeof row.firebase_uid !== 'string' || row.firebase_uid.length === 0) return null;
  return { firebaseUid: row.firebase_uid, verified: row.verified === true };
}

/**
 * Proves ownership of a Firebase uid by handing the attachments Worker a Firebase ID token, which
 * checks its RS256 signature against Google's published keys before recording a verified link.
 *
 * A verified link is the only kind that is exclusive, so this is also how a real owner takes their
 * uid back from an account that merely asserted it. Returns false when the Worker is not
 * configured for verification — the unverified claim still stands, and no longer locks anyone out.
 */
export async function linkVerifiedFirebaseUid(firebaseIdToken: string): Promise<boolean> {
  const workerUrl = loadAttachmentsWorkerUrl();
  if (!workerUrl) return false;

  const {
    data: { session },
  } = await getSupabaseClient().auth.getSession();
  if (!session) throw new Error('Supabase session missing');

  const response = await fetch(`${workerUrl.replace(/\/$/, '')}/v1/identity/firebase-link`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${session.access_token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ firebaseIdToken }),
  });

  if (response.status === 501) return false;
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(body.error ?? `Firebase uid verification failed (${response.status})`);
  }
  return true;
}
