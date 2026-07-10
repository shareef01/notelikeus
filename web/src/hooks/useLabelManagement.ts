import { useCallback } from 'react';
import { useNoteLabels } from '@/hooks/useNoteLabels';
import { useNotesStore } from '@/store/notesStore';
import { labelFromName } from '@/types/label';
import type { Label } from '@/types/label';

export function useLabelManagement() {
  const labels = useNoteLabels();
  const notes = useNotesStore((state) => state.notes);
  const upsertLocalNote = useNotesStore((state) => state.upsertLocalNote);

  const updateLabel = useCallback(
    (id: string, name: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;

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
    [notes, upsertLocalNote],
  );

  const deleteLabel = useCallback(
    (id: string) => {
      for (const note of notes) {
        if (!note.labels.some((label) => label.id === id)) continue;
        upsertLocalNote({
          ...note,
          labels: note.labels.filter((label) => label.id !== id),
          timestamp: Date.now(),
        });
      }
    },
    [notes, upsertLocalNote],
  );

  const createLabel = useCallback((_name: string) => {
    // Web labels are derived from notes; new names are assigned from the editor.
  }, []);

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
