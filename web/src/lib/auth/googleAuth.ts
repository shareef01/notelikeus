import { signInWithGoogleSupabase, signOutSupabase } from '@/lib/auth/supabaseAuth';
import { deleteAllSupabaseCloudData } from '@/lib/supabase/deleteAllUserCloudData';

export async function signInWithGoogle(): Promise<void> {
  await signInWithGoogleSupabase();
}

export async function signOutGoogle(options: { deleteCloudData?: boolean } = {}): Promise<void> {
  if (options.deleteCloudData) {
    await deleteAllSupabaseCloudData();
  }
  await signOutSupabase();
}
