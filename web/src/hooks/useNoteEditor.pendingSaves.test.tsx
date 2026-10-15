import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import type { Note } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let finishSave: (() => void) | null = null;
vi.mock('@/lib/notes/noteActions', () => ({
  saveNote: vi.fn(
    (_note: Note) =>
      new Promise<void>((resolve) => {
        finishSave = resolve;
      }),
  ),
  removeNote: vi.fn(),
}));

import { useNoteEditor } from '@/hooks/useNoteEditor';
import { hasPendingEditorSaves, whenEditorSavesSettled } from '@/lib/notes/pendingEditorSaves';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

type Editor = ReturnType<typeof useNoteEditor>;

function mount(noteId: string): { current: () => Editor; unmount: () => void } {
  let latest: Editor | null = null;
  function Probe() {
    latest = useNoteEditor(noteId);
    return null;
  }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  return { current: () => latest as unknown as Editor, unmount: () => act(() => root.unmount()) };
}

// The service-worker update reloads the page once these settle (main.tsx). A reload while an edit
// is still waiting on its debounce or its write would drop it (N-08).
describe('pending editor saves', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    finishSave = null;
    useNotesStore.getState().reset();
    useNotesStore.getState().setNotes([
      createEmptyNote({ id: '1', localId: 1, title: 'Groceries', content: 'milk', timestamp: 900 }),
    ]);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('holds from the first keystroke until the save has finished writing', async () => {
    const editor = mount('1');
    expect(hasPendingEditorSaves()).toBe(false);

    act(() => editor.current().setContent('milk, eggs'));
    expect(hasPendingEditorSaves()).toBe(true);
    let settled = false;
    void whenEditorSavesSettled().then(() => {
      settled = true;
    });

    // The debounce fires and the save starts, but has not finished writing.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(finishSave).not.toBeNull();
    expect(hasPendingEditorSaves()).toBe(true);
    expect(settled).toBe(false);

    await act(async () => {
      finishSave?.();
      await vi.runAllTimersAsync();
    });
    expect(hasPendingEditorSaves()).toBe(false);
    expect(settled).toBe(true);
    editor.unmount();
  });

  it('settles when the editor closes with nothing left to save', () => {
    const editor = mount('1');
    editor.unmount();
    expect(hasPendingEditorSaves()).toBe(false);
  });
});
