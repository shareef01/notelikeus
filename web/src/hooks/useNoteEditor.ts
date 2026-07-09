import {
  buildNoteFromEditor,
  createBlankEditorState,
  DEFAULT_EDITOR_COLOR,
  editorStateFromNote,
  type EditorState,
} from '@/store/editorTypes';
import { useNoteLabels } from '@/hooks/useNoteLabels';
import { saveNote, removeNote } from '@/lib/notes/noteActions';
import { useNotesStore } from '@/store/notesStore';
import { createChecklistItem, sortChecklistItems } from '@/types/checklist';
import type { Label } from '@/types/label';
import { allocateLocalNoteId } from '@/types/note';
import { labelFromName } from '@/types/label';
import { noteColorsForTheme } from '@/theme/colors';
import { cancelNoteReminder, scheduleNoteReminder } from '@/lib/reminders/reminderScheduler';
import { useCallback, useEffect, useRef, useState } from 'react';

const AUTOSAVE_MS = 1000;

function nextNotePosition(): number {
  const notes = useNotesStore.getState().notes.filter((n) => !n.isArchived && !n.isTrashed);
  return notes.reduce((max, note) => Math.max(max, note.position), -1) + 1;
}

function isNoteEmpty(state: EditorState): boolean {
  return !state.title.trim() && !state.content.trim() && state.checklist.length === 0;
}

export function useNoteEditor(noteId: string | 'new' | null) {
  const sourceTimestamp = useNotesStore((state) => {
    if (!noteId || noteId === 'new') return null;
    return state.notes.find((note) => note.id === noteId)?.timestamp ?? null;
  });
  const allLabels = useNoteLabels();

  const [state, setState] = useState<EditorState>(createBlankEditorState());
  const autosaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stateRef = useRef(state);
  const loadedRouteRef = useRef<string | null>(null);
  stateRef.current = state;

  useEffect(() => {
    if (!noteId) {
      loadedRouteRef.current = null;
      return;
    }

    if (loadedRouteRef.current === noteId) return;

    if (noteId === 'new') {
      const defaultColor = noteColorsForTheme(true)[0] ?? DEFAULT_EDITOR_COLOR;
      setState(createBlankEditorState(defaultColor, nextNotePosition()));
      loadedRouteRef.current = noteId;
      return;
    }

    const existing = useNotesStore.getState().notes.find((note) => note.id === noteId);
    if (existing) {
      setState(editorStateFromNote(existing));
      loadedRouteRef.current = noteId;
    }
  }, [noteId, sourceTimestamp]);

  const persistNow = useCallback(async () => {
    const current = stateRef.current;
    if (isNoteEmpty(current)) return;

    setState((prev) => ({ ...prev, isSaving: true }));

    let working = { ...current };
    const updatedTimestamp = Date.now();

    if (!working.id || working.localId == null) {
      const localId = allocateLocalNoteId(useNotesStore.getState().notes);
      const id = String(localId);
      working = {
        ...working,
        id,
        localId,
        position: working.position || nextNotePosition(),
      };
    }

    working = { ...working, timestamp: updatedTimestamp };

    const note = buildNoteFromEditor(working);
    if (!note) {
      setState((prev) => ({ ...prev, isSaving: false }));
      return;
    }

    await saveNote(note);
    scheduleNoteReminder(note);
    setState({ ...working, isSaving: false, lastSavedAt: updatedTimestamp });
  }, []);

  const scheduleAutosave = useCallback(() => {
    if (autosaveTimer.current) clearTimeout(autosaveTimer.current);
    autosaveTimer.current = setTimeout(() => {
      void persistNow();
    }, AUTOSAVE_MS);
  }, [persistNow]);

  useEffect(() => {
    return () => {
      if (autosaveTimer.current) clearTimeout(autosaveTimer.current);
    };
  }, []);

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
    setContent: (content: string) => patch((s) => ({ ...s, content })),
    setColor: (color: number) => patch((s) => ({ ...s, color })),
    togglePin: () => patch((s) => ({ ...s, isPinned: !s.isPinned })),
    toggleArchive: () => patch((s) => ({ ...s, isArchived: !s.isArchived })),
    toggleLock: () =>
      patch((s) => ({
        ...s,
        isLocked: !s.isLocked,
        isAccessGranted: s.isLocked ? true : s.isAccessGranted,
      })),
    grantAccess: () => patch((s) => ({ ...s, isAccessGranted: true })),
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
    setReminderTimestamp: (reminderTimestamp: number | null) =>
      patch((s) => ({ ...s, reminderTimestamp })),
    clearReminder: () => patch((s) => ({ ...s, reminderTimestamp: null })),
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
      patch((s) => ({ ...s, content: result.text }));
      return result;
    },
    flushSave: persistNow,
    trashNote: async () => {
      const updated = { ...stateRef.current, isTrashed: true };
      stateRef.current = updated;
      setState(updated);
      cancelNoteReminder(updated.id ?? '');
      await persistNow();
    },
    deleteNote: async () => {
      const current = stateRef.current;
      if (current.id) {
        cancelNoteReminder(current.id);
        await removeNote(current.id);
      }
    },
  };
}
