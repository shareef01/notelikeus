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
