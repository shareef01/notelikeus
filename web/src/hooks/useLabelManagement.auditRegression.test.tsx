import { expect, it } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { useLabelManagement } from '@/hooks/useLabelManagement';
import { useNotesStore } from '@/store/notesStore';
import { useAuthStore } from '@/store/authStore';
import { createEmptyNote } from '@/types/note';
import { listNotes, listPendingPushes, putNotes } from '@/lib/local/notesLocalRepository';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

it('AUD-E07 persists label renames and records pending note edits', async () => {
  await resetNotesDatabaseForTests();
  useAuthStore.getState().setUser({ uid: 'A', email: null, displayName: null });
  const note = createEmptyNote({ id: '1', localId: 1, labels: [{ id: 'label', name: 'Before' }] });
  await putNotes('A', [note]);
  useNotesStore.getState().setNotes([note]);
  let labels!: ReturnType<typeof useLabelManagement>;
  function Probe() { labels = useLabelManagement(); return null; }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  await act(async () => { await labels.updateLabel('label', 'After'); });
  expect(useNotesStore.getState().notes[0]?.labels[0]?.name).toBe('After');
  expect((await listNotes('A'))[0]?.labels[0]?.name).toBe('After');
  expect(await listPendingPushes('A')).toHaveProperty('1');
  act(() => root.unmount());
});
