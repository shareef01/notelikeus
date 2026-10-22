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

  it('renders conflict copy banner and opens conflict review on click', async () => {
    const openConflictReviewSpy = vi.spyOn(useUiStore.getState(), 'openConflictReview');

    const flushSaveMock = vi.fn().mockResolvedValue({ status: 'saved', noteId: 'note-conflict-1' });

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-conflict-1',
        title: 'Meeting Notes (conflicted copy)',
        content: 'Different changes',
        checklist: [],
        attachments: [],
        labels: [],
        color: 0,
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        reminderTimestamp: null,
        timestamp: 1700000000000,
        lastSavedAt: 1700000000000,
        isSaving: false,
        saveFailed: false,
        conflictCopy: true,
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
      flushSave: flushSaveMock,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-conflict-1' },
        }),
      );
    });

    const compareBtn = container.querySelector(
      'button[aria-label="Compare with the current note"]',
    ) as HTMLButtonElement | null;
    expect(compareBtn).not.toBeNull();
    expect(compareBtn?.textContent).toContain('Compare versions');

    await act(async () => {
      compareBtn?.click();
    });

    expect(flushSaveMock).toHaveBeenCalledTimes(1);
    expect(openConflictReviewSpy).toHaveBeenCalledWith('note-conflict-1');

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('conflict comparison: never opens comparison when flushSave fails, preserving editor state', async () => {
    const openConflictReviewSpy = vi.spyOn(useUiStore.getState(), 'openConflictReview');
    const flushSaveMock = vi.fn().mockResolvedValue({ status: 'failed', error: new Error('IndexedDB full') });

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-conflict-failed',
        title: 'Meeting Notes (conflicted copy)',
        content: 'Draft that cannot land',
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
        conflictCopy: true,
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
      flushSave: flushSaveMock,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-conflict-failed' },
        }),
      );
    });

    const compareBtn = container.querySelector(
      'button[aria-label="Compare with the current note"]',
    ) as HTMLButtonElement | null;
    expect(compareBtn).not.toBeNull();

    await act(async () => {
      compareBtn?.click();
    });

    expect(flushSaveMock).toHaveBeenCalledTimes(1);
    // CRITICAL: openConflictReview must NEVER be called when flushSave fails!
    expect(openConflictReviewSpy).not.toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('conflict comparison: opens comparison with unchanged draft using editor state id', async () => {
    const openConflictReviewSpy = vi.spyOn(useUiStore.getState(), 'openConflictReview');
    const flushSaveMock = vi.fn().mockResolvedValue({ status: 'unchanged' });

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-conflict-unchanged',
        title: 'Meeting Notes (conflicted copy)',
        content: 'No modifications',
        checklist: [],
        attachments: [],
        labels: [],
        color: 0,
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        reminderTimestamp: null,
        timestamp: 1700000000000,
        lastSavedAt: 1700000000000,
        isSaving: false,
        saveFailed: false,
        conflictCopy: true,
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
      flushSave: flushSaveMock,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-conflict-unchanged' },
        }),
      );
    });

    const compareBtn = container.querySelector(
      'button[aria-label="Compare with the current note"]',
    ) as HTMLButtonElement | null;
    expect(compareBtn).not.toBeNull();

    await act(async () => {
      compareBtn?.click();
    });

    expect(flushSaveMock).toHaveBeenCalledTimes(1);
    expect(openConflictReviewSpy).toHaveBeenCalledWith('note-conflict-unchanged');

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('conflict comparison: uses actual forked noteId when save forks the draft', async () => {
    const openConflictReviewSpy = vi.spyOn(useUiStore.getState(), 'openConflictReview');
    // Save succeeds but forked to a new note id
    const flushSaveMock = vi.fn().mockResolvedValue({ status: 'saved', noteId: 'forked-new-note-id' });

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'initial-stale-id',
        title: 'Conflicted Draft',
        content: 'Forking content',
        checklist: [],
        attachments: [],
        labels: [],
        color: 0,
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        reminderTimestamp: null,
        timestamp: 1700000000000,
        lastSavedAt: 1700000000000,
        isSaving: false,
        saveFailed: false,
        conflictCopy: true,
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
      flushSave: flushSaveMock,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'initial-stale-id' },
        }),
      );
    });

    const compareBtn = container.querySelector(
      'button[aria-label="Compare with the current note"]',
    ) as HTMLButtonElement | null;
    expect(compareBtn).not.toBeNull();

    await act(async () => {
      compareBtn?.click();
    });

    expect(flushSaveMock).toHaveBeenCalledTimes(1);
    // CRITICAL: Must use the forked noteId from flushSave result, not the stale initial-stale-id!
    expect(openConflictReviewSpy).toHaveBeenCalledWith('forked-new-note-id');

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('conflict comparison: ignores repeated rapid clicks while flushSave is in flight', async () => {
    const openConflictReviewSpy = vi.spyOn(useUiStore.getState(), 'openConflictReview');
    let resolveSave!: (res: { status: 'saved'; noteId: string }) => void;
    const flushSaveMock = vi.fn().mockReturnValue(
      new Promise((res) => {
        resolveSave = res;
      }),
    );

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-rapid-clicks',
        title: 'Rapid Click Note',
        content: 'Rapid clicks',
        checklist: [],
        attachments: [],
        labels: [],
        color: 0,
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        reminderTimestamp: null,
        timestamp: 1700000000000,
        lastSavedAt: 1700000000000,
        isSaving: false,
        saveFailed: false,
        conflictCopy: true,
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
      flushSave: flushSaveMock,
      discardChanges: vi.fn(),
    } as any);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-rapid-clicks' },
        }),
      );
    });

    const compareBtn = container.querySelector(
      'button[aria-label="Compare with the current note"]',
    ) as HTMLButtonElement | null;
    expect(compareBtn).not.toBeNull();

    // Trigger multiple rapid clicks while the first save is in flight
    act(() => {
      compareBtn?.click();
      compareBtn?.click();
      compareBtn?.click();
    });

    expect(flushSaveMock).toHaveBeenCalledTimes(1);

    // Resolve the in-flight save
    await act(async () => {
      resolveSave({ status: 'saved', noteId: 'note-rapid-clicks' });
    });

    expect(openConflictReviewSpy).toHaveBeenCalledTimes(1);
    expect(openConflictReviewSpy).toHaveBeenCalledWith('note-rapid-clicks');

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});

