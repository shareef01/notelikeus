import { deleteAllCloudData } from '@/lib/firestore/notesRepository';
import { resetCloudMergeState } from '@/lib/notes/notesSyncService';
import { getFirebaseAuth, initFirebase } from '@/lib/firebase';
import { GoogleAuthProvider, signInWithPopup, signOut } from 'firebase/auth';

export async function signInWithGoogle(): Promise<void> {
  initFirebase();
  const auth = getFirebaseAuth();
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  await signInWithPopup(auth, provider);
}

export async function signOutGoogle(deleteCloudData = false): Promise<void> {
  initFirebase();
  const auth = getFirebaseAuth();
  const userId = auth.currentUser?.uid;

  if (deleteCloudData && userId) {
    await deleteAllCloudData(userId);
  }

  await signOut(auth);
  resetCloudMergeState();
}
