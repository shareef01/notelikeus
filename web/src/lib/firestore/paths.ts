import {
  collection,
  doc,
  type CollectionReference,
  type DocumentReference,
} from 'firebase/firestore';
import { getFirestoreDb } from '@/lib/firebase';

export function userRoot(userId: string): DocumentReference {
  return doc(getFirestoreDb(), 'users', userId);
}

export function userNotesCollection(userId: string): CollectionReference {
  return collection(getFirestoreDb(), 'users', userId, 'notes');
}

export function userNoteDocument(userId: string, noteId: string | number): DocumentReference {
  return doc(getFirestoreDb(), 'users', userId, 'notes', String(noteId));
}

export function userSyncMetaDocument(userId: string): DocumentReference {
  return doc(getFirestoreDb(), 'users', userId, '_meta', 'sync');
}

export function userAttachmentsCollection(userId: string): CollectionReference {
  return collection(getFirestoreDb(), 'users', userId, 'attachments');
}
