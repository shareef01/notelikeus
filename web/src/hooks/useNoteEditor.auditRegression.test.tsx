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

it('viewing and closing an existing note preserves its date without writing', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-11-06T12:00:00Z'));
  vi.mocked(saveNote).mockReset().mockResolvedValue(undefined);
  const original = createEmptyNote({ id: '1', localId: 1, content: 'old note', timestamp: 1000 });
  useNotesStore.getState().setNotes([original]);
  let editor!: ReturnType<typeof useNoteEditor>;
  function Probe() { editor = useNoteEditor('1'); return null; }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  try {
    await act(async () => { expect(await editor.flushSave()).toEqual({ status: 'unchanged' }); });
    expect(saveNote).not.toHaveBeenCalled();
    expect(editor.state.timestamp).toBe(1000);
    expect(useNotesStore.getState().notes[0].timestamp).toBe(1000);
  } finally { act(() => root.unmount()); }
});

it('a real edit changes the date once but closing again does not resave it', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-11-06T12:00:00Z'));
  vi.mocked(saveNote).mockReset().mockResolvedValue(undefined);
  useNotesStore.getState().setNotes([
    createEmptyNote({ id: '1', localId: 1, content: 'old note', timestamp: 1000 }),
  ]);
  let editor!: ReturnType<typeof useNoteEditor>;
  function Probe() { editor = useNoteEditor('1'); return null; }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  try {
    act(() => editor.setContent('edited note'));
    await act(async () => { await editor.flushSave(); });
    const editedAt = editor.state.timestamp;
    expect(saveNote).toHaveBeenCalledTimes(1);
    expect(editedAt).toBe(Date.now());
    vi.setSystemTime(new Date('2026-11-07T12:00:00Z'));
    await act(async () => { expect(await editor.flushSave()).toEqual({ status: 'unchanged' }); });
    expect(saveNote).toHaveBeenCalledTimes(1);
    expect(editor.state.timestamp).toBe(editedAt);
  } finally { act(() => root.unmount()); }
});

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
