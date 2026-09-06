import {
  createEmailPasswordAccountSupabase,
  signInWithEmailPasswordSupabase,
} from '@/lib/auth/supabaseAuth';
import { isTestLoginEnabled, testLoginBuildEnabled } from '@/lib/auth/testLoginFlag';

export { isTestLoginEnabled, testLoginBuildEnabled };

export async function signInWithEmailPassword(email: string, password: string): Promise<void> {
  if (!isTestLoginEnabled()) {
    throw new Error('Email/password sign-in is only available in development');
  }
  await signInWithEmailPasswordSupabase(email, password);
}

export async function createEmailPasswordAccount(
  email: string,
  password: string,
): Promise<void> {
  if (!isTestLoginEnabled()) {
    throw new Error('Email/password sign-in is only available in development');
  }
  await createEmailPasswordAccountSupabase(email, password);
}
