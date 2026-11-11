import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';

vi.mock('@/lib/notes/noteActions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/notes/noteActions')>()),
  saveNote: vi.fn().mockResolvedValue(undefined),
}));

import { saveNote } from '@/lib/notes/noteActions';
import { useNoteActions } from '@/screens/main/useNoteActions';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import { createEmptyNote, filterNotes, type Note } from '@/types/note';

type Actions = ReturnType<typeof useNoteActions>;

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}`, timestamp: 1, ...overrides });
}

/** Renders the hook against the store's notes in manual order, as MainScreen does. */
function Harness({ onActions }: { onActions: (actions: Actions) => void }) {
  const notes = useNotesStore((state) => state.notes);
  const filteredNotes = filterNotes(notes, { filter: 'active', sortOrder: 'manual' });
  onActions(
    useNoteActions({
      notes,
      filteredNotes,
      getSelectedSnapshots: () => [],
      selectionAllPinned: false,
      clearSelection: () => {},
      closeEmptyTrashConfirm: () => {},
      closeBulkDeleteConfirm: () => {},
    }),
  );
  return null;
}

const shownIds = () =>
  filterNotes(useNotesStore.getState().notes, { filter: 'active', sortOrder: 'manual' }).map(
    (n) => n.id,
  );

const flush = () => act(async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
});

describe('useNoteActions Move up / Move down', () => {
  let container: HTMLDivElement;
  let root: Root;
  let actions: Actions;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.mocked(saveNote).mockReset().mockResolvedValue(undefined);
    useToastStore.setState({ message: null });
    useNotesStore.getState().reset();
    // Store order deliberately differs from the shown order.
    useNotesStore.getState().setNotes([
      note('3', { position: 2 }),
      note('1', { position: 0 }),
      note('p', { position: 0, isPinned: true }),
      note('2', { position: 1 }),
    ]);
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    act(() => {
      root.render(createElement(Harness, { onActions: (next) => (actions = next) }));
    });
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  it('moves a note down one place, announces where it went and syncs the positions', async () => {
    expect(shownIds()).toEqual(['p', '1', '2', '3']);
    act(() => actions.moveNoteBy('1', 1));
    await flush();

    expect(shownIds()).toEqual(['p', '2', '1', '3']);
    expect(useToastStore.getState().message?.text).toBe('Moved to position 3 of 4');
    const saved = vi.mocked(saveNote).mock.calls.map(([n]) => [n.id, n.position]);
    // The shown list is renumbered by index, as native `writeNotePositions` does: p 0, 2 1, 1 2,
    // 3 3. Only the notes whose number changed are written.
    expect(saved).toEqual([
      ['3', 3],
      ['1', 2],
    ]);
    for (const [n] of vi.mocked(saveNote).mock.calls) {
      expect(n.timestamp).toBeGreaterThan(1);
    }
  });

  it('moves a note up one place', async () => {
    act(() => actions.moveNoteBy('3', -1));
    await flush();
    expect(shownIds()).toEqual(['p', '1', '3', '2']);
    expect(useToastStore.getState().message?.text).toBe('Moved to position 3 of 4');
  });

  it('says why a note cannot move instead of moving it', async () => {
    act(() => actions.moveNoteBy('3', 1));
    expect(useToastStore.getState().message?.text).toBe('Already at the bottom');
    act(() => actions.moveNoteBy('p', -1));
    expect(useToastStore.getState().message?.text).toBe('Already at the top');
    act(() => actions.moveNoteBy('1', -1));
    expect(useToastStore.getState().message?.text).toBe('Pinned notes stay above the others');
    await flush();
    expect(shownIds()).toEqual(['p', '1', '2', '3']);
    expect(saveNote).not.toHaveBeenCalled();
  });

  it('puts the note back when the save fails', async () => {
    vi.mocked(saveNote).mockRejectedValue(new Error('offline'));
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    act(() => actions.moveNoteBy('1', 1));
    await flush();
    expect(shownIds()).toEqual(['p', '1', '2', '3']);
    expect(useToastStore.getState().message?.tone).toBe('error');
  });
});
