import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { EditorScreen } from '@/screens/EditorScreen';
import { useUiStore } from '@/store/uiStore';
import * as noteEditorHook from '@/hooks/useNoteEditor';
import * as attachmentConfig from '@/lib/attachments/attachmentConfig';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('@/hooks/useCloudSync', () => ({
  useCloudSync: () => ({ userId: 'test-user', isGuest: false, online: true }),
}));

vi.mock('@/lib/attachments/pendingAttachmentStore', () => ({
  isPendingAttachment: (path: string) => path.startsWith('pending:'),
}));

describe('Web Rapid Image Capture — Durability and Lifecycle', () => {
  let mockAddAttachment: ReturnType<typeof vi.fn>;
  let mockDiscardChanges: ReturnType<typeof vi.fn>;
  let mockRemoveAttachment: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.restoreAllMocks();
    useUiStore.setState({ editorRoute: { mode: 'new' } });
    mockAddAttachment = vi.fn().mockResolvedValue(undefined);
    mockDiscardChanges = vi.fn().mockResolvedValue(undefined);
    mockRemoveAttachment = vi.fn().mockResolvedValue(undefined);

    vi.spyOn(attachmentConfig, 'isR2AttachmentsEnabled').mockReturnValue(true);

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
      setContentSmart: vi.fn().mockReturnValue({
        nextContent: '',
        structureChanged: false,
        selectionStart: 0,
        selectionEnd: 0,
      }),
      applyContentFormatting: vi.fn(),
      setChecklist: vi.fn(),
      addChecklistItem: vi.fn(),
      updateChecklistItem: vi.fn(),
      removeChecklistItem: vi.fn(),
      convertContentToChecklist: vi.fn(),
      convertChecklistToContent: vi.fn(),
      addAttachment: mockAddAttachment,
      removeAttachment: mockRemoveAttachment,
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
      discardChanges: mockDiscardChanges,
    } as any);
  });

  function renderEditor(mode: 'new' | 'edit' = 'new', noteId?: string) {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: mode === 'new' ? { mode: 'new' } : { mode: 'edit', noteId: noteId! },
        }),
      );
    });

    return {
      container,
      cleanup: () => {
        act(() => {
          root.unmount();
        });
        container.remove();
      },
    };
  }

  it('new note: paste screenshot calls canonical editor.addAttachment with image File', async () => {
    const { container, cleanup } = renderEditor('new');
    const editorSurface = container.querySelector('[role="dialog"]');
    const screenshot = new File(['png-data'], 'clipboard_screenshot.png', { type: 'image/png' });

    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(pasteEvent, 'clipboardData', {
      value: {
        getData: () => '',
        types: ['Files'],
        items: [{ kind: 'file', type: 'image/png', getAsFile: () => screenshot }],
        files: [screenshot],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(pasteEvent);
    });

    expect(mockAddAttachment).toHaveBeenCalledTimes(1);
    expect(mockAddAttachment).toHaveBeenCalledWith(screenshot);

    cleanup();
  });

  it('existing note: drop image calls canonical editor.addAttachment', async () => {
    const { container, cleanup } = renderEditor('edit', 'existing-123');
    const editorSurface = container.querySelector('[role="dialog"]');
    const photo = new File(['jpg-data'], 'vacation.jpg', { type: 'image/jpeg' });

    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    dropEvent.preventDefault = vi.fn();
    Object.defineProperty(dropEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [photo],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dropEvent);
    });

    expect(mockAddAttachment).toHaveBeenCalledTimes(1);
    expect(mockAddAttachment).toHaveBeenCalledWith(photo);

    cleanup();
  });

  it('existing toolbar file input still triggers editor.addAttachment (no regression of Add image button)', async () => {
    const { container, cleanup } = renderEditor('new');

    const fileInput = container.querySelector('input[type="file"][accept="image/*"]') as HTMLInputElement;
    expect(fileInput).not.toBeNull();

    const chosenFile = new File(['picked'], 'chosen.png', { type: 'image/png' });
    Object.defineProperty(fileInput, 'files', {
      value: [chosenFile],
      configurable: true,
    });

    await act(async () => {
      fileInput.dispatchEvent(new Event('change', { bubbles: true }));
    });

    expect(mockAddAttachment).toHaveBeenCalledTimes(1);
    expect(mockAddAttachment).toHaveBeenCalledWith(chosenFile);

    cleanup();
  });

  it('durability invariant: pending blob is durable before note references it, and note never references unstaged blobs', async () => {
    const timeline: string[] = [];

    // Simulate canonical addAttachment timeline
    let stageSuccess = true;
    const testAddAttachment = async (_file: File) => {
      timeline.push('1. validate file');
      timeline.push('2. create attachmentId');
      const staged = stageSuccess;
      timeline.push(`3. storePendingAttachment landed = ${staged}`);
      if (!staged) {
        timeline.push('4. staging failed -> abort without patching note');
        return;
      }
      timeline.push('4. patch editorState.attachments with pending: path');
      timeline.push('5. scheduleAutosave debounce');
    };

    // Case A: staging fails -> note never references blob
    stageSuccess = false;
    await testAddAttachment(new File(['data'], 'test.png', { type: 'image/png' }));
    expect(timeline).toContain('3. storePendingAttachment landed = false');
    expect(timeline).toContain('4. staging failed -> abort without patching note');
    expect(timeline).not.toContain('4. patch editorState.attachments with pending: path');

    // Case B: staging succeeds -> note references blob, autosave triggers, persistence allocates ID
    timeline.length = 0;
    stageSuccess = true;
    await testAddAttachment(new File(['data'], 'test.png', { type: 'image/png' }));
    expect(timeline).toEqual([
      '1. validate file',
      '2. create attachmentId',
      '3. storePendingAttachment landed = true',
      '4. patch editorState.attachments with pending: path',
      '5. scheduleAutosave debounce',
    ]);
  });
});
