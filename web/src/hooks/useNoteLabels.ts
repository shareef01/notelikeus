import { useMemo } from 'react';
import { notesContentKey } from '@/lib/notes/noteEquality';
import { useNotesStore } from '@/store/notesStore';
import type { Label } from '@/types/label';

function collectLabels(notes: ReturnType<typeof useNotesStore.getState>['notes']): Label[] {
  const map = new Map<string, Label>();
  for (const note of notes) {
    for (const label of note.labels) {
      map.set(label.name.toLowerCase(), label);
    }
  }
  return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
}

export function useNoteLabels(): Label[] {
  const notes = useNotesStore((state) => state.notes);
  const notesKey = notesContentKey(notes);
  return useMemo(() => collectLabels(notes), [notesKey, notes]);
}
