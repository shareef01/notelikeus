import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { EditorScreen } from '@/screens/EditorScreen';
import { useUiStore } from '@/store/uiStore';
import * as noteEditorHook from '@/hooks/useNoteEditor';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('@/hooks/useCloudSync', () => ({
  useCloudSync: () => ({ userId: 'test-user', isGuest: false, online: true }),
}));

/**
 * AU-20: a new note moves focus to its body one animation frame after it mounts. A user who had
 * already clicked into the title in that window had the focus taken from under them, so the rest
 * of the title was typed into the body and the note was saved without one.
 */
describe('EditorScreen new-note autofocus', () => {
  let frames: FrameRequestCallback[];

  beforeEach(() => {
    vi.restoreAllMocks();
    frames = [];
    vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    vi.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation((id) => {
      frames[id - 1] = () => {};
    });
    useUiStore.setState({ editorRoute: { mode: 'new' } });
    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: null,
        title: '',
        content: '',
        checklist: [],
        attachments: [],
        labels: [],
        color: 0,
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        reminderTimestamp: null,
        timestamp: 1700000000000,
        lastSavedAt: null,
        isSaving: false,
        saveFailed: false,
      },
      setTitle: vi.fn(),
      setContent: vi.fn(),
      setContentSmart: vi.fn(),
      applyContentFormatting: vi.fn(),
      setChecklist: vi.fn(),
      addChecklistItem: vi.fn(),
      updateChecklistItem: vi.fn(),
      removeChecklistItem: vi.fn(),
      convertContentToChecklist: vi.fn(),
      convertChecklistToContent: vi.fn(),
      addAttachment: vi.fn(),
      removeAttachment: vi.fn(),
      setColor: vi.fn(),
      togglePinned: vi.fn(),
      togglePin: vi.fn(),
      setArchived: vi.fn(),
      toggleArchive: vi.fn(),
      trashNote: vi.fn(),
      setReminder: vi.fn(),
      setReminderTimestamp: vi.fn(),
      clearReminder: vi.fn(),
      createLabel: vi.fn(),
      toggleLabel: vi.fn(),
      allLabels: [],
      flushSave: vi.fn().mockResolvedValue({ status: 'saved' }),
      discardChanges: vi.fn(),
    } as never);
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  function renderNewNote() {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    act(() => {
      root.render(createElement(EditorScreen, { route: { mode: 'new' } }));
    });
    return {
      title: container.querySelector<HTMLElement>('#note-title')!,
      body: container.querySelector<HTMLElement>('[aria-label="Note body"]')!,
      runFrames: () =>
        act(() => {
          for (const frame of frames.splice(0)) frame(0);
        }),
      unmount: () => act(() => root.unmount()),
    };
  }

  it('focuses the body of a new note', () => {
    const editor = renderNewNote();
    editor.runFrames();
    expect(document.activeElement).toBe(editor.body);
    editor.unmount();
  });

  it('leaves focus in the title when the user got there first', () => {
    const editor = renderNewNote();
    editor.title.focus();
    editor.runFrames();
    expect(document.activeElement).toBe(editor.title);
    editor.unmount();
  });
});
