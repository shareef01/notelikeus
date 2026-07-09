import type { ChecklistItem } from '@/types/checklist';
import type { Label } from '@/types/label';
import type { Note } from '@/types/note';

export interface EditorState {
  id: string | null;
  localId: number | null;
  title: string;
  content: string;
  color: number;
  isPinned: boolean;
  isArchived: boolean;
  isTrashed: boolean;
  isLocked: boolean;
  isAccessGranted: boolean;
  reminderTimestamp: number | null;
  labels: Label[];
  checklist: ChecklistItem[];
  timestamp: number;
  position: number;
  isLoaded: boolean;
  isSaving: boolean;
  lastSavedAt: number | null;
}

export const DEFAULT_EDITOR_COLOR = 0xff1a1a1a | 0;

export function buildNoteFromEditor(state: EditorState): Note | null {
  if (!state.id || state.localId == null) return null;
  if (!state.title.trim() && !state.content.trim() && state.checklist.length === 0) return null;

  return {
    id: state.id,
    localId: state.localId,
    title: state.title,
    content: state.content,
    timestamp: state.timestamp,
    color: state.color,
    isPinned: state.isPinned,
    isArchived: state.isArchived,
    isTrashed: state.isTrashed,
    position: state.position,
    isLocked: state.isLocked,
    reminderTimestamp: state.reminderTimestamp,
    labels: state.labels,
    attachments: [],
    checklist: state.checklist,
  };
}

export function editorStateFromNote(note: Note): EditorState {
  return {
    id: note.id,
    localId: note.localId,
    title: note.title,
    content: note.content,
    color: note.color,
    isPinned: note.isPinned,
    isArchived: note.isArchived,
    isTrashed: note.isTrashed,
    isLocked: note.isLocked,
    isAccessGranted: !note.isLocked,
    reminderTimestamp: note.reminderTimestamp,
    labels: note.labels,
    checklist: [...note.checklist].sort((a, b) => {
      if (a.isChecked !== b.isChecked) return a.isChecked ? 1 : -1;
      return a.position - b.position;
    }),
    timestamp: note.timestamp,
    position: note.position,
    isLoaded: true,
    isSaving: false,
    lastSavedAt: note.timestamp,
  };
}

export function createBlankEditorState(color = DEFAULT_EDITOR_COLOR, position = 0): EditorState {
  return {
    id: null,
    localId: null,
    title: '',
    content: '',
    color,
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    isLocked: false,
    isAccessGranted: true,
    reminderTimestamp: null,
    labels: [],
    checklist: [],
    timestamp: Date.now(),
    position,
    isLoaded: true,
    isSaving: false,
    lastSavedAt: null,
  };
}
