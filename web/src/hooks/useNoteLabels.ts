import { useMemo } from 'react';
import { notesContentKey } from '@/lib/notes/noteEquality';
import { useNotesStore } from '@/store/notesStore';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import type { Label } from '@/types/label';

function collectLabels(
  notes: ReturnType<typeof useNotesStore.getState>['notes'],
  registered: Record<string, Label>,
): Label[] {
  const map = new Map<string, Label>();
  for (const label of Object.values(registered)) {
    map.set(label.id, label);
  }
  for (const note of notes) {
    for (const label of note.labels) {
      map.set(label.id, label);
    }
  }
  return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
}

export function useNoteLabels(): Label[] {
  const notes = useNotesStore((state) => state.notes);
  const registered = useLabelRegistryStore((state) => state.labels);
  const notesKey = notesContentKey(notes);
  // notesKey is a content-derived guard: `notes` gets a new reference on every store update
  // even when no label-relevant content changed, so it's deliberately left out of the deps.
  return useMemo(() => collectLabels(notes, registered), [notesKey, registered]);
}
