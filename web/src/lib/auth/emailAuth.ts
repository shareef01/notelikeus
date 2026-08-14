import { getFirebaseAuth, initFirebase } from '@/lib/firebase';
import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
} from 'firebase/auth';
import { FirebaseError } from 'firebase/app';

/** Dev-only email/password login. Requires Email/Password enabled in Firebase Auth. */
/**
 * Email/password sign-in is a development affordance, not a product feature — the app signs in
 * with Google. It is also enabled for the end-to-end build, which is a production build in every
 * other respect and needs a way to reach a signed-in state against the Auth emulator.
 *
 * `VITE_E2E` is set only by web/.env.e2e, which only `--mode e2e` loads, so a normal `vite build`
 * cannot turn this on. The same flag gates the emulator redirect in lib/firebase.ts, so an e2e
 * build can only ever sign in against a local emulator, never a real account.
 */
export function isTestLoginEnabled(): boolean {
  return import.meta.env.DEV === true || Boolean(import.meta.env.VITE_E2E);
}

export async function signInWithEmailPassword(email: string, password: string): Promise<void> {
  if (!isTestLoginEnabled()) {
    throw new Error('Email/password sign-in is only available in development');
  }
  initFirebase();
  await signInWithEmailAndPassword(getFirebaseAuth(), email.trim(), password);
}

export async function createEmailPasswordAccount(
  email: string,
  password: string,
): Promise<void> {
  if (!isTestLoginEnabled()) {
    throw new Error('Email/password sign-in is only available in development');
  }
  initFirebase();
  const auth = getFirebaseAuth();
  const trimmed = email.trim();
  try {
    await createUserWithEmailAndPassword(auth, trimmed, password);
  } catch (error) {
    // Common when retrying Create: treat as sign-in so the button "just works".
    if (error instanceof FirebaseError && error.code === 'auth/email-already-in-use') {
      await signInWithEmailAndPassword(auth, trimmed, password);
      return;
    }
    throw error;
  }
}
