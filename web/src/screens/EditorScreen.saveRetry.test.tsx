import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { EditorScreen } from '@/screens/EditorScreen';
import { useUiStore } from '@/store/uiStore';
import * as noteEditorHook from '@/hooks/useNoteEditor';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('@/hooks/useCloudSync', () => ({
  useCloudSync: () => ({ userId: 'test-user', isGuest: false, online: true }),
}));

vi.mock('@/lib/attachments/pendingAttachmentStore', () => ({
  isPendingAttachment: () => false,
}));

describe('UX-A — EditorScreen save failure retry banner', () => {
  beforeEach(() => {
    useUiStore.setState({ editorRoute: { mode: 'edit', noteId: 'note-1' } });
  });

  it('flushes save on Retry save but does NOT close the editor', async () => {
    const closeEditorSpy = vi.spyOn(useUiStore.getState(), 'closeEditor');
    const mockFlushSave = vi.fn().mockResolvedValue({ status: 'saved' });

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-1',
        title: 'Draft Note',
        content: 'Unsaved changes',
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
        saveFailed: true,
      },
      setTitle: vi.fn(),
      setContent: vi.fn(),
      applyContentFormatting: vi.fn(),
      setChecklist: vi.fn(),
      addChecklistItem: vi.fn(),
      updateChecklistItem: vi.fn(),
      removeChecklistItem: vi.fn(),
      convertChecklistToContent: vi.fn(),
      addAttachment: vi.fn(),
      removeAttachment: vi.fn(),
      setColor: vi.fn(),
      togglePinned: vi.fn(),
      setArchived: vi.fn(),
      trashNote: vi.fn(),
      setReminder: vi.fn(),
      addLabel: vi.fn(),
      removeLabel: vi.fn(),
      flushSave: mockFlushSave,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-1' },
        }),
      );
    });

    const retryBtn = Array.from(container.querySelectorAll('button')).find(
      (btn) => btn.textContent?.trim() === 'Retry save',
    );
    expect(retryBtn).toBeDefined();

    await act(async () => {
      retryBtn?.click();
    });

    // flushSave should have been called to attempt saving
    expect(mockFlushSave).toHaveBeenCalledTimes(1);

    // CRITICAL (UX-A): Editor must NOT be closed when retrying a save!
    expect(closeEditorSpy).not.toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('Path B: editor remains open and displays error when save retry fails again', async () => {
    const closeEditorSpy = vi.spyOn(useUiStore.getState(), 'closeEditor');
    const mockFlushSave = vi.fn().mockResolvedValue({ status: 'failed' });

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-1',
        title: 'Draft Note',
        content: 'Unsaved changes',
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
        saveFailed: true,
      },
      setTitle: vi.fn(),
      setContent: vi.fn(),
      applyContentFormatting: vi.fn(),
      setChecklist: vi.fn(),
      addChecklistItem: vi.fn(),
      updateChecklistItem: vi.fn(),
      removeChecklistItem: vi.fn(),
      convertChecklistToContent: vi.fn(),
      addAttachment: vi.fn(),
      removeAttachment: vi.fn(),
      setColor: vi.fn(),
      togglePinned: vi.fn(),
      setArchived: vi.fn(),
      trashNote: vi.fn(),
      setReminder: vi.fn(),
      addLabel: vi.fn(),
      removeLabel: vi.fn(),
      flushSave: mockFlushSave,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-1' },
        }),
      );
    });

    const retryBtn = Array.from(container.querySelectorAll('button')).find(
      (btn) => btn.textContent?.trim() === 'Retry save',
    );
    expect(retryBtn).toBeDefined();

    await act(async () => {
      retryBtn?.click();
    });

    expect(mockFlushSave).toHaveBeenCalledTimes(1);
    expect(closeEditorSpy).not.toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('Path C: explicit back button closes editor when save flush succeeds', async () => {
    const closeEditorSpy = vi.spyOn(useUiStore.getState(), 'closeEditor');
    const mockFlushSave = vi.fn().mockResolvedValue({ status: 'saved' });

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-1',
        title: 'Draft Note',
        content: 'Unsaved changes',
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
      applyContentFormatting: vi.fn(),
      setChecklist: vi.fn(),
      addChecklistItem: vi.fn(),
      updateChecklistItem: vi.fn(),
      removeChecklistItem: vi.fn(),
      convertChecklistToContent: vi.fn(),
      addAttachment: vi.fn(),
      removeAttachment: vi.fn(),
      setColor: vi.fn(),
      togglePinned: vi.fn(),
      setArchived: vi.fn(),
      trashNote: vi.fn(),
      setReminder: vi.fn(),
      addLabel: vi.fn(),
      removeLabel: vi.fn(),
      flushSave: mockFlushSave,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-1' },
        }),
      );
    });

    const backBtn = container.querySelector('button[aria-label="Back"]');
    expect(backBtn).toBeDefined();

    await act(async () => {
      (backBtn as HTMLButtonElement)?.click();
    });

    expect(mockFlushSave).toHaveBeenCalledTimes(1);
    expect(closeEditorSpy).toHaveBeenCalledTimes(1);

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
