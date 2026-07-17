import { getFirebaseAuth, initFirebase } from '@/lib/firebase';
import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
} from 'firebase/auth';
import { FirebaseError } from 'firebase/app';

/** Dev / QA email-password login. Requires Email/Password enabled in Firebase Auth. */
export function isTestLoginEnabled(): boolean {
  return (
    import.meta.env.DEV === true ||
    import.meta.env.VITE_ENABLE_TEST_LOGIN === 'true' ||
    import.meta.env.VITE_ENABLE_TEST_LOGIN === '1'
  );
}

export async function signInWithEmailPassword(email: string, password: string): Promise<void> {
  initFirebase();
  await signInWithEmailAndPassword(getFirebaseAuth(), email.trim(), password);
}

export async function createEmailPasswordAccount(
  email: string,
  password: string,
): Promise<void> {
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
