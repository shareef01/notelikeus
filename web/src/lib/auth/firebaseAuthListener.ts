import { onAuthStateChanged } from 'firebase/auth';
import type { AuthUser } from '@/lib/auth/authUser';
import { authUserFromFirebase } from '@/lib/auth/authUser';
import { getFirebaseAuth } from '@/lib/firebase';

export function initFirebaseAuthListener(
  onUser: (user: AuthUser | null) => void,
): () => void {
  const auth = getFirebaseAuth();
  return onAuthStateChanged(auth, (nextUser) => {
    onUser(nextUser ? authUserFromFirebase(nextUser) : null);
  });
}
