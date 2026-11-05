import { getSupabaseClient } from '@/lib/supabase/client';
import { useAuthStore } from '@/store/authStore';

/** Capture one owner's bearer; a delayed request must never resolve credentials from a later session. */
export async function ownerAccessToken(ownerId: string): Promise<string> {
  const { data, error } = await getSupabaseClient().auth.getSession();
  if (error) throw error;
  const session = data.session;
  if (useAuthStore.getState().user?.uid !== ownerId || session?.user.id !== ownerId || !session.access_token?.trim()) {
    throw new Error('Account changed during sync; local changes remain saved for their owner');
  }
  return session.access_token;
}
