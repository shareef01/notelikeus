import {
  createEmailPasswordAccountSupabase,
  signInWithEmailPasswordSupabase,
} from '@/lib/auth/supabaseAuth';

/**
 * Email/password sign-in is a development affordance, not a product feature — the app signs in
 * with Google. It is also enabled for the end-to-end build, which is a production build in every
 * other respect and needs a way to reach a signed-in state against local Supabase.
 *
 * `VITE_E2E` is set only by web/.env.e2e, which only `--mode e2e` loads, so a normal `vite build`
 * cannot turn this on.
 */
export const testLoginBuildEnabled =
  import.meta.env.DEV === true || Boolean(import.meta.env.VITE_E2E);

export function isTestLoginEnabled(): boolean {
  return testLoginBuildEnabled;
}

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
