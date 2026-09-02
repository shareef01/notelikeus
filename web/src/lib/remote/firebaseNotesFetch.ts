import { getDocs, orderBy, query } from 'firebase/firestore';
import { cloudMapToNote, type FirestoreNoteDocument } from '@/lib/mappers/noteCloudMapper';
import { userNotesCollection } from '@/lib/firestore/paths';
import type { Label } from '@/types/label';
import type { Note } from '@/types/note';

function createLabelResolver(): (name: string) => Label {
  const labelCache = new Map<string, Label>();
  return (name: string): Label => {
    const key = name.trim().toLowerCase();
    const cached = labelCache.get(key);
    if (cached) return cached;
    const label: Label = { id: `label-${key}`, name: name.trim() };
    labelCache.set(key, label);
    return label;
  };
}

/** One-shot authoritative read of all cloud notes for hydration into IndexedDB. */
export async function fetchAllNotes(userId: string): Promise<Note[]> {
  const snapshot = await getDocs(query(userNotesCollection(userId), orderBy('timestamp', 'desc')));
  const resolveLabel = createLabelResolver();
  return snapshot.docs.map((docSnap) =>
    cloudMapToNote(docSnap.id, docSnap.data() as FirestoreNoteDocument, resolveLabel),
  );
}
