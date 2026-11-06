import { captureOwnerScope } from '@/lib/local/ownerScope';
import { putLocalEdits, putNotes } from '@/lib/local/notesLocalRepository';
import { getRemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSourceRegistry';
import { useAuthStore } from '@/store/authStore';
import { useCallback } from 'react';
import { useNoteLabels } from '@/hooks/useNoteLabels';
import { useNotesStore } from '@/store/notesStore';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { labelFromName, labelNameKey } from '@/types/label';
import type { Label } from '@/types/label';

export function useLabelManagement() {
  const labels = useNoteLabels();

  const addRegisteredLabel = useLabelRegistryStore((state) => state.addLabel);
  const renameRegisteredLabel = useLabelRegistryStore((state) => state.renameLabel);
  const removeRegisteredLabel = useLabelRegistryStore((state) => state.removeLabel);

  const updateLabel = useCallback(
    async (id: string, name: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;
      if (labels.some(label => label.id !== id && labelNameKey(label.name) === labelNameKey(trimmed))) throw new Error('A label with this name already exists.');

      await persistLabels(id, note => ({ ...note,
        labels: note.labels.map(label => label.id === id ? labelFromName(trimmed, id) : label),
        timestamp: Date.now(),
      }), () => renameRegisteredLabel(id, trimmed));
    },
    [renameRegisteredLabel, labels],
  );

  const deleteLabel = useCallback(
    async (id: string) => {
      await persistLabels(id, note => ({ ...note,
        labels: note.labels.filter(label => label.id !== id), timestamp: Date.now(),
      }), () => removeRegisteredLabel(id));
    },
    [removeRegisteredLabel],
  );

  const createLabel = useCallback(
    (name: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;
      const candidate = labelFromName(trimmed);
      if (labels.some(label => label.id === candidate.id || labelNameKey(label.name) === labelNameKey(trimmed))) throw new Error('A label with this name already exists.');
      addRegisteredLabel(trimmed);
    },
    [addRegisteredLabel, labels],
  );

  return {
    labels,
    createLabel,
    updateLabel,
    deleteLabel,
  } satisfies {
    labels: Label[];
    createLabel: (name: string) => void;
    updateLabel: (id: string, name: string) => Promise<void>;
    deleteLabel: (id: string) => Promise<void>;
  };
}

async function persistLabels(id: string, update: (note: import('@/types/note').Note) => import('@/types/note').Note, publishRegistry: () => void) {
  const scope = captureOwnerScope();
  if (!scope.ownerId) throw new Error('No active library');
  const updates = useNotesStore.getState().notes.filter(n => n.labels.some(l => l.id === id)).map(update);
  const uid = useAuthStore.getState().user?.uid;
  if (uid) await putLocalEdits(scope.ownerId, updates);
  else await putNotes(scope.ownerId, updates);
  if (!scope.isActive()) return;
  for (const note of updates) useNotesStore.getState().upsertLocalNote(note);
  publishRegistry();
  if (uid) await getRemoteNotesDataSource().pushPendingEdits?.(uid).catch(() => {});
}
