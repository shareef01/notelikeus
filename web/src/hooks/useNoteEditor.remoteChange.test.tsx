import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import type { Note } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const saved: Note[] = [];
vi.mock('@/lib/notes/noteActions', () => ({
  saveNote: vi.fn(async (note: Note) => {
    saved.push(note);
    const { useNotesStore } = await import('@/store/notesStore');
    useNotesStore.getState().upsertLocalNote(note);
  }),
  removeNote: vi.fn(),
}));

import { useNoteEditor } from '@/hooks/useNoteEditor';
import { decideEditorSave } from '@/lib/notes/editorConflict';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

type Editor = ReturnType<typeof useNoteEditor>;

function opened(): Note {
  return createEmptyNote({ id: '1', localId: 1, title: 'Groceries', content: 'milk', timestamp: 900, serverUpdatedAt: 1000 });
}

/** The phone's edit, arriving while the note is open here. */
function remoteEdit(): Note {
  return { ...opened(), content: 'milk, eggs (from phone)', timestamp: 1900, serverUpdatedAt: 2000 };
}

function mount(noteId: string): { current: () => Editor; unmount: () => void } {
  let latest: Editor | null = null;
  function Probe() {
    latest = useNoteEditor(noteId);
    return null;
  }
  const container = document.createElement('div');
  const root = createRoot(container);
  act(() => root.render(createElement(Probe)));
  return {
    current: () => latest as unknown as Editor,
    unmount: () => act(() => root.unmount()),
  };
}

describe('editor and a remote change to the open note', () => {
  beforeEach(() => {
    saved.length = 0;
    useNotesStore.getState().reset();
    useNotesStore.getState().setNotes([opened()]);
  });

  it('typing against a stale version is saved as a conflicted copy, never over the newer one', async () => {
    const editor = mount('1');
    act(() => editor.current().setContent('milk, bread (typed here)'));
    act(() => useNotesStore.getState().upsertLocalNote(remoteEdit()));

    await act(async () => {
      await editor.current().flushSave();
    });

    expect(saved).toHaveLength(1);
    expect(saved[0]?.id).not.toBe('1');
    expect(saved[0]?.content).toBe('milk, bread (typed here)');
    expect(saved[0]?.title).toBe('Groceries (conflicted copy)');
    expect(useNotesStore.getState().notes.find((note) => note.id === '1')?.content).toBe('milk, eggs (from phone)');
    expect(editor.current().state.conflictCopy).toBe(true);
    expect(editor.current().state.id).toBe(saved[0]?.id);
    editor.unmount();
  });

  it('with nothing typed, the editor shows the newer version instead', () => {
    const editor = mount('1');
    act(() => useNotesStore.getState().upsertLocalNote(remoteEdit()));

    expect(editor.current().state.content).toBe('milk, eggs (from phone)');
    expect(editor.current().state.conflictCopy).toBeFalsy();
    editor.unmount();
  });
});

describe('decideEditorSave', () => {
  const base = opened();

  it('saves over a note nobody else touched', () => {
    expect(decideEditorSave({ draft: { ...base, content: 'x' }, stored: base, lastSaved: base })).toBe('save');
  });

  it("treats this device's own echo as a straight edit", () => {
    const echo = { ...base, content: 'mine', serverUpdatedAt: 3000 };
    expect(
      decideEditorSave({ draft: { ...base, content: 'mine, more' }, stored: echo, lastSaved: { ...base, content: 'mine' } }),
    ).toBe('adopt-stamp');
  });

  it('adopts a first stamp for a draft that never had one', () => {
    expect(decideEditorSave({ draft: { ...base, serverUpdatedAt: null }, stored: base, lastSaved: null })).toBe('adopt-stamp');
  });

  it('forks when another device changed the words', () => {
    expect(decideEditorSave({ draft: { ...base, content: 'x' }, stored: remoteEdit(), lastSaved: base })).toBe('fork');
  });
});
