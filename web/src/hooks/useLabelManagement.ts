import { useCallback } from 'react';
import { useNoteLabels } from '@/hooks/useNoteLabels';
import { useNotesStore } from '@/store/notesStore';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { labelFromName } from '@/types/label';
import type { Label } from '@/types/label';

export function useLabelManagement() {
  const labels = useNoteLabels();
  const notes = useNotesStore((state) => state.notes);
  const upsertLocalNote = useNotesStore((state) => state.upsertLocalNote);
  const addRegisteredLabel = useLabelRegistryStore((state) => state.addLabel);
  const renameRegisteredLabel = useLabelRegistryStore((state) => state.renameLabel);
  const removeRegisteredLabel = useLabelRegistryStore((state) => state.removeLabel);

  const updateLabel = useCallback(
    (id: string, name: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;

      renameRegisteredLabel(id, trimmed);
      for (const note of notes) {
        if (!note.labels.some((label) => label.id === id)) continue;
        upsertLocalNote({
          ...note,
          labels: note.labels.map((label) =>
            label.id === id ? labelFromName(trimmed, id) : label,
          ),
          timestamp: Date.now(),
        });
      }
    },
    [notes, upsertLocalNote, renameRegisteredLabel],
  );

  const deleteLabel = useCallback(
    (id: string) => {
      removeRegisteredLabel(id);
      for (const note of notes) {
        if (!note.labels.some((label) => label.id === id)) continue;
        upsertLocalNote({
          ...note,
          labels: note.labels.filter((label) => label.id !== id),
          timestamp: Date.now(),
        });
      }
    },
    [notes, upsertLocalNote, removeRegisteredLabel],
  );

  const createLabel = useCallback(
    (name: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;
      addRegisteredLabel(trimmed);
    },
    [addRegisteredLabel],
  );

  return {
    labels,
    createLabel,
    updateLabel,
    deleteLabel,
  } satisfies {
    labels: Label[];
    createLabel: (name: string) => void;
    updateLabel: (id: string, name: string) => void;
    deleteLabel: (id: string) => void;
  };
}
