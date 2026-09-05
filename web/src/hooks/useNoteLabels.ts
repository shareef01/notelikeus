import { useMemo } from 'react';
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
  return useMemo(() => collectLabels(notes, registered), [notes, registered]);
}
