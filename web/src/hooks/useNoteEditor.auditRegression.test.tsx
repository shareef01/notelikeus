import { afterEach, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';

vi.mock('@/lib/notes/noteActions', () => ({ saveNote: vi.fn(), removeNote: vi.fn() }));
import { saveNote } from '@/lib/notes/noteActions';
import { useNoteEditor } from '@/hooks/useNoteEditor';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
afterEach(() => { vi.useRealTimers(); });

// Regression tests for slow saves and intentionally emptied notes.
it('AUD-E01 preserves newer typing when an older save finishes', async () => {
  vi.useFakeTimers();
  useNotesStore.getState().setNotes([createEmptyNote({ id: '1', localId: 1, content: 'initial' })]);
  let finish!: () => void;
  vi.mocked(saveNote).mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; }));
  let editor!: ReturnType<typeof useNoteEditor>;
  function Probe() { editor = useNoteEditor('1'); return null; }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  act(() => editor.setContent('first edit'));
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  act(() => editor.setContent('first edit plus newer words'));
  expect(editor.state.content).toBe('first edit plus newer words');
  await act(async () => { finish(); });
  expect(editor.state.content).toBe('first edit plus newer words');
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(vi.mocked(saveNote).mock.calls.at(-1)?.[0].content).toBe('first edit plus newer words');
  act(() => root.unmount());
});

it('AUD-E11 saves an intentionally emptied existing note', async () => {
  vi.useFakeTimers(); vi.mocked(saveNote).mockClear();
  useNotesStore.getState().setNotes([createEmptyNote({ id: '1', localId: 1, content: 'secret to erase' })]);
  let editor!: ReturnType<typeof useNoteEditor>;
  function Probe() { editor = useNoteEditor('1'); return null; }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  act(() => editor.setContent(''));
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(editor.state.content).toBe('');
  expect(saveNote).toHaveBeenCalledWith(expect.objectContaining({ content: '' }));

  act(() => root.unmount());
});
