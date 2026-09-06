import {
  buildNoteFromEditor,
  createBlankEditorState,
  DEFAULT_EDITOR_COLOR,
  editorStateFromNote,
  type EditorState,
} from '@/store/editorTypes';
import { useNoteLabels } from '@/hooks/useNoteLabels';
import { MAX_NOTE_CONTENT_CHARS, MAX_NOTE_TITLE_CHARS } from '@/lib/backup/constants';
import { saveNote, removeNote } from '@/lib/notes/noteActions';
import { requestNotificationPermission } from '@/lib/reminders/reminderScheduler';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import { createChecklistItem, sortChecklistItems } from '@/types/checklist';
import type { Label } from '@/types/label';
import { labelFromName } from '@/types/label';
import { processSmartText, type TextEdit } from '@/lib/text/smartTextProcessor';
import { createCoalescedPersister } from '@/lib/notes/coalescedPersister';
import { allocateLocalNoteIdForOwner } from '@/lib/local/localNoteIdAllocator';
import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import {
  createAttachmentId,
  isPendingAttachment,
  MAX_ATTACHMENT_BYTES,
  pendingStoragePath,
} from '@/lib/attachments/attachmentPaths';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import {
  releasePendingAttachment,
  storePendingAttachment,
  rebindPendingAttachmentNote,
} from '@/lib/attachments/pendingAttachmentStore';
import { revokeAttachmentPreviewUrl } from '@/lib/attachments/attachmentPreviewCache';
import { useCallback, useEffect, useRef, useState } from 'react';

const AUTOSAVE_MS = 1000;

function nextNotePosition(): number {
  const notes = useNotesStore.getState().notes.filter((n) => !n.isArchived && !n.isTrashed);
  return notes.reduce((max, note) => Math.max(max, note.position), -1) + 1;
}

function isNoteEmpty(state: EditorState): boolean {
  return (
    !state.title.trim() &&
    !state.content.trim() &&
    state.checklist.length === 0 &&
    state.attachments.length === 0
  );
}

export function useNoteEditor(noteId: string | 'new' | null) {
  const sourceTimestamp = useNotesStore((state) => {
    if (!noteId || noteId === 'new') return null;
    return state.notes.find((note) => note.id === noteId)?.timestamp ?? null;
  });
  const sourceServerUpdatedAt = useNotesStore((state) => {
    if (!noteId || noteId === 'new') return null;
    return state.notes.find((note) => note.id === noteId)?.serverUpdatedAt ?? null;
  });
  const allLabels = useNoteLabels();

  const [state, setState] = useState<EditorState>(() => {
    if (noteId && noteId !== 'new') {
      const existing = useNotesStore.getState().notes.find((note) => note.id === noteId);
      if (existing) {
        return editorStateFromNote(existing);
      }
    }
    const filterColor = useNotesStore.getState().filters.colorArgb;
    return createBlankEditorState(
      filterColor ?? DEFAULT_EDITOR_COLOR,
      nextNotePosition(),
    );
  });
  const autosaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stateRef = useRef(state);
  const loadedRouteRef = useRef<string | null>(noteId);
  const routeRef = useRef(noteId);
  const lastContentEditRef = useRef<TextEdit>({
    text: state.content,
    selectionStart: state.content.length,
    selectionEnd: state.content.length,
  });
  stateRef.current = state;

  const persisterRef = useRef(
    createCoalescedPersister<EditorState>(async (snapshot, generation) => {
      const persister = persisterRef.current;
      const savingForRoute = loadedRouteRef.current;
      const isCurrentRoute = () => loadedRouteRef.current === savingForRoute;
      const stillLatest = () =>
        persister != null &&
        generation === persister.generation &&
        !persister.hasQueued;

      if (isNoteEmpty(snapshot)) return;
      if (
        routeRef.current &&
        routeRef.current !== 'new' &&
        loadedRouteRef.current !== routeRef.current
      ) {
        return;
      }

      if (isCurrentRoute()) {
        setState((prev) => ({ ...prev, isSaving: true, persistStatus: 'saving' }));
      }

      let working = { ...snapshot };
      const updatedTimestamp = Date.now();

      if (!working.id || working.localId == null) {
        const ownerId = resolveOwnerId() ?? GUEST_OWNER_ID;
        const existingMax = useNotesStore
          .getState()
          .notes.reduce((max, note) => Math.max(max, note.localId), 0);
        const localId = await allocateLocalNoteIdForOwner(ownerId, existingMax);
        const id = String(localId);
        working = {
          ...working,
          id,
          localId,
          position: working.position || nextNotePosition(),
          attachments: working.attachments.map((attachment) => ({
            ...attachment,
            noteId: localId,
          })),
        };
        for (const attachment of working.attachments) {
          if (isPendingAttachment(attachment.storagePath)) {
            await rebindPendingAttachmentNote(attachment.id, id);
          }
        }
      }

      working = {
        ...working,
        title: working.title.slice(0, MAX_NOTE_TITLE_CHARS),
        content: working.content.slice(0, MAX_NOTE_CONTENT_CHARS),
        timestamp: updatedTimestamp,
      };

      const stored = working.id
        ? useNotesStore.getState().notes.find((n) => n.id === working.id)
        : undefined;
      if (working.serverUpdatedAt == null && stored?.serverUpdatedAt != null) {
        working = { ...working, serverUpdatedAt: stored.serverUpdatedAt };
      }

      const note = buildNoteFromEditor(working);
      if (!note) {
        if (isCurrentRoute() && stillLatest()) {
          setState((prev) => ({ ...prev, isSaving: false, persistStatus: 'idle' }));
        }
        return;
      }

      try {
        const result = await saveNote(note);
        if (!isCurrentRoute() || !stillLatest()) return;
        const persistStatus = result.error
          ? result.localSaved
            ? result.attachmentPending
              ? 'attachment-pending'
              : 'saved-local'
            : 'error'
          : result.attachmentPending
            ? 'attachment-pending'
            : result.remoteSynced
              ? 'synced'
              : 'saved-local';
        if (result.error && result.localSaved) {
          useToastStore
            .getState()
            .show(
              result.attachmentPending
                ? 'Saved locally. Attachment will upload when you are back online.'
                : 'Saved locally. Sync will retry when you are back online.',
              'default',
            );
        }
        setState({
          ...working,
          isSaving: false,
          persistStatus,
          lastSavedAt: updatedTimestamp,
        });
      } catch (error) {
        console.warn('[Notelikeus] Note save failed:', error);
        if (isCurrentRoute() && stillLatest()) {
          useToastStore.getState().show('Could not save changes to this device.', 'error');
          setState((prev) => ({ ...prev, isSaving: false, persistStatus: 'error' }));
        }
      }
    }),
  );

  const persistNow = useCallback(async () => {
    if (autosaveTimer.current) {
      clearTimeout(autosaveTimer.current);
      autosaveTimer.current = null;
    }
    await persisterRef.current.request(stateRef.current);
  }, []);

  const scheduleAutosave = useCallback(() => {
    if (autosaveTimer.current) clearTimeout(autosaveTimer.current);
    autosaveTimer.current = setTimeout(() => {
      void persistNow();
    }, AUTOSAVE_MS);
  }, [persistNow]);

  routeRef.current = noteId;

  useEffect(() => {
    if (!noteId) {
      loadedRouteRef.current = null;
      return;
    }

    if (loadedRouteRef.current === noteId) return;

    // Flush any pending autosave for the note being navigated away from —
    // stateRef still holds its content until setState below replaces it.
    if (autosaveTimer.current) void persistNow();

    if (noteId === 'new') {
      const filterColor = useNotesStore.getState().filters.colorArgb;
      const blank = createBlankEditorState(
        filterColor ?? DEFAULT_EDITOR_COLOR,
        nextNotePosition(),
      );
      setState(blank);
      lastContentEditRef.current = { text: '', selectionStart: 0, selectionEnd: 0 };
      loadedRouteRef.current = noteId;
      return;
    }

    const existing = useNotesStore.getState().notes.find((note) => note.id === noteId);
    if (existing) {
      setState(editorStateFromNote(existing));
      loadedRouteRef.current = noteId;
      lastContentEditRef.current = {
        text: existing.content,
        selectionStart: existing.content.length,
        selectionEnd: existing.content.length,
      };
    }
    // else: the route points at a note that isn't in the store (e.g. a stale ?note= link or a
    // note deleted on another device). loadedRouteRef stays null, so persistNow sees an edit
    // route that never loaded and refuses to allocate a fresh id — no phantom note is minted,
    // and the editor stays inert until the effect re-runs once the note (if ever) arrives.
  }, [noteId, sourceTimestamp, persistNow]);

  useEffect(() => {
    if (!noteId || noteId === 'new') return;
    if (loadedRouteRef.current !== noteId) return;
    if (sourceServerUpdatedAt == null) return;
    if (stateRef.current.serverUpdatedAt != null) return;
    const next = { ...stateRef.current, serverUpdatedAt: sourceServerUpdatedAt };
    stateRef.current = next;
    setState(next);
  }, [noteId, sourceServerUpdatedAt]);

  // Flush on tab close/refresh and on backgrounding (mobile browsers don't
  // reliably fire beforeunload) so a pending debounce never silently drops.
  useEffect(() => {
    const flushIfPending = () => {
      if (autosaveTimer.current) void persistNow();
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') flushIfPending();
    };
    window.addEventListener('pagehide', flushIfPending);
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.removeEventListener('pagehide', flushIfPending);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      if (autosaveTimer.current) void persistNow();
    };
  }, [persistNow]);

  const patch = useCallback(
    (updater: (prev: EditorState) => EditorState) => {
      setState((prev) => {
        const next = updater(prev);
        stateRef.current = next;
        return next;
      });
      scheduleAutosave();
    },
    [scheduleAutosave],
  );

  return {
    state,
    allLabels,
    setTitle: (title: string) => patch((s) => ({ ...s, title })),
    setContent: (content: string) => {
      lastContentEditRef.current = {
        text: content,
        selectionStart: content.length,
        selectionEnd: content.length,
      };
      patch((s) => ({ ...s, content }));
    },
    setContentSmart: (
      content: string,
      selectionStart: number,
      selectionEnd: number,
    ): { selectionStart: number; selectionEnd: number; structureChanged?: boolean } => {
      const previous = lastContentEditRef.current;
      const current: TextEdit = { text: content, selectionStart, selectionEnd };
      const result = processSmartText(current, previous);

      if (result.structureChanged) {
        lastContentEditRef.current = current;
        return { selectionStart, selectionEnd, structureChanged: true };
      }

      lastContentEditRef.current = result.edit;
      patch((s) => ({ ...s, content: result.edit.text }));
      return {
        selectionStart: result.edit.selectionStart,
        selectionEnd: result.edit.selectionEnd,
      };
    },
    setColor: (color: number) => patch((s) => ({ ...s, color })),
    togglePin: () => patch((s) => ({ ...s, isPinned: !s.isPinned })),
    toggleArchive: () => patch((s) => ({ ...s, isArchived: !s.isArchived })),
    toggleLabel: (label: Label) =>
      patch((s) => {
        const exists = s.labels.some((entry) => entry.id === label.id);
        return {
          ...s,
          labels: exists
            ? s.labels.filter((entry) => entry.id !== label.id)
            : [...s.labels, label],
        };
      }),
    createLabel: (name: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;
      const existing = allLabels.find((l) => l.name.toLowerCase() === trimmed.toLowerCase());
      const label = existing ?? labelFromName(trimmed);
      patch((s) => {
        if (s.labels.some((entry) => entry.id === label.id)) return s;
        return { ...s, labels: [...s.labels, label] };
      });
    },
    updateChecklistItem: (id: string, text: string, isChecked: boolean) =>
      patch((s) => {
        const checklist = s.checklist.map((item) =>
          item.id === id ? { ...item, text, isChecked } : item,
        );
        return { ...s, checklist: sortChecklistItems(checklist) };
      }),
    addChecklistItem: () =>
      patch((s) => ({
        ...s,
        checklist: [
          ...s.checklist,
          createChecklistItem({ text: '', position: s.checklist.length, isChecked: false }),
        ],
      })),
    removeChecklistItem: (id: string) =>
      patch((s) => ({
        ...s,
        checklist: s.checklist.filter((item) => item.id !== id),
      })),
    convertContentToChecklist: () =>
      patch((s) => {
        if (s.checklist.length > 0) return s;
        const lines = s.content
          .split('\n')
          .map((line) => line.trim())
          .filter(Boolean);
        const checklist =
          lines.length === 0
            ? [createChecklistItem({ text: '', position: 0, isChecked: false })]
            : lines.map((text, index) =>
                createChecklistItem({ text, position: index, isChecked: false }),
              );
        return { ...s, content: '', checklist };
      }),
    convertChecklistToContent: () =>
      patch((s) => {
        if (s.checklist.length === 0) return s;
        const content = s.checklist.map((item) => item.text.trim()).join('\n');
        return { ...s, content, checklist: [] };
      }),
    // Async and validating so every entry point (the bell-icon dialog, the options sheet's
    // quick-pick chips, and its date field) gets the same checks instead of each needing its
    // own copy — a reminder saved without notification permission would silently never fire.
    setReminderTimestamp: async (reminderTimestamp: number | null) => {
      if (reminderTimestamp == null) {
        patch((s) => ({ ...s, reminderTimestamp: null }));
        return;
      }
      if (reminderTimestamp <= Date.now()) {
        useToastStore.getState().show('Choose a future date and time', 'error');
        return;
      }
      const granted = await requestNotificationPermission();
      if (!granted) {
        useToastStore.getState().show('Enable notifications to use reminders', 'error');
        return;
      }
      patch((s) => ({ ...s, reminderTimestamp }));
    },
    clearReminder: () => patch((s) => ({ ...s, reminderTimestamp: null })),
    addAttachment: async (file: File) => {
      if (!isR2AttachmentsEnabled()) {
        useToastStore.getState().show('Attachments are not enabled', 'error');
        return;
      }
      if (!file.type.startsWith('image/')) {
        useToastStore.getState().show('Only images are supported', 'error');
        return;
      }
      if (file.size > MAX_ATTACHMENT_BYTES) {
        useToastStore.getState().show('Image must be under 10 MB', 'error');
        return;
      }
      const attachmentId = createAttachmentId();
      await storePendingAttachment(attachmentId, file, file.type, stateRef.current.id);
      patch((s) => ({
        ...s,
        attachments: [
          ...s.attachments,
          {
            id: attachmentId,
            noteId: s.localId ?? 0,
            storagePath: pendingStoragePath(attachmentId),
            type: 'image',
            mimeType: file.type,
            sizeBytes: file.size,
          },
        ],
      }));
    },
    removeAttachment: (attachmentId: string) => {
      const current = stateRef.current;
      const removed = current.attachments.find((attachment) => attachment.id === attachmentId);
      if (removed && current.id && !isPendingAttachment(removed.storagePath)) {
        void getAttachmentBlobStore()
          .delete(current.id, attachmentId)
          .catch(() => {});
      }
      void releasePendingAttachment(attachmentId);
      if (current.id) {
        revokeAttachmentPreviewUrl(current.id, attachmentId);
      }
      patch((s) => ({
        ...s,
        attachments: s.attachments.filter((attachment) => attachment.id !== attachmentId),
      }));
    },
    applyContentFormatting: (
      updater: (
        text: string,
        selectionStart: number,
        selectionEnd: number,
      ) => { text: string; selectionStart: number; selectionEnd: number } | null,
      selectionStart: number,
      selectionEnd: number,
    ) => {
      const current = stateRef.current;
      const result = updater(current.content, selectionStart, selectionEnd);
      if (!result) return null;
      lastContentEditRef.current = {
        text: result.text,
        selectionStart: result.selectionStart,
        selectionEnd: result.selectionEnd,
      };
      patch((s) => ({ ...s, content: result.text }));
      return result;
    },
    flushSave: persistNow,
    trashNote: async () => {
      const updated = { ...stateRef.current, isTrashed: true };
      stateRef.current = updated;
      setState(updated);
      await persistNow();
    },
    deleteNote: async () => {
      // Cancel the debounce first. Otherwise the unmount flush below runs persistNow after the
      // delete, and pushNote's upsertLocalNote puts the note straight back into the store — the
      // cloud copy stays deleted via the tombstone, so the user sees it reappear until the next
      // merge quietly removes it again.
      if (autosaveTimer.current) {
        clearTimeout(autosaveTimer.current);
        autosaveTimer.current = null;
      }
      const current = stateRef.current;
      if (current.id) {
        await removeNote(current.id);
      }
    },
  };
}
