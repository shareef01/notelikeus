import type { ChecklistItem } from './checklist';
import type { Label } from './label';
import type { Attachment } from './attachment';
import { noteColorsMatch } from '@/theme/colors';

/**
 * Canonical note model — field names match Android Room + Firestore cloud map.
 * `timestamp` is the conflict-resolution clock (Android uses this, not `updatedAt`).
 */
export interface Note {
  /** Firestore document id (string form of Android localId). */
  id: string;
  /** Numeric local id shared with Android (`localId` in cloud documents). */
  localId: number;
  title: string;
  content: string;
  timestamp: number;
  color: number;
  isPinned: boolean;
  isArchived: boolean;
  isTrashed: boolean;
  position: number;
  isLocked: boolean;
  reminderTimestamp: number | null;
  labels: Label[];
  attachments: Attachment[];
  checklist: ChecklistItem[];
}

/** Alias for consumers expecting an `updatedAt` field in the PWA directive. */
export type NoteUpdatedAt = Pick<Note, 'timestamp'>;

export type NoteFilter = 'active' | 'archived' | 'trashed';

export interface NoteQueryFilters {
  filter: NoteFilter;
  searchQuery?: string;
  colorArgb?: number | null;
  labelName?: string | null;
  sortOrder?: 'manual' | 'newest' | 'oldest';
}

export function isCloudSyncEligible(note: Note): boolean {
  return !note.isLocked;
}

export function createEmptyNote(partial: Partial<Note> & Pick<Note, 'localId' | 'id'>): Note {
  const now = Date.now();
  return {
    title: '',
    content: '',
    timestamp: now,
    color: partial.color ?? (0xff1a1a1a | 0),
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    position: 0,
    isLocked: false,
    reminderTimestamp: null,
    labels: [],
    attachments: [],
    checklist: [],
    ...partial,
    id: partial.id,
    localId: partial.localId,
  };
}

export function allocateLocalNoteId(existing: Note[]): number {
  const maxId = existing.reduce((max, note) => Math.max(max, note.localId), 0);
  const candidate = Date.now();
  return Math.max(maxId + 1, candidate);
}

export function filterNotes(notes: Note[], filters: NoteQueryFilters): Note[] {
  const query = filters.searchQuery?.trim().toLowerCase() ?? '';
  let result = notes.filter((note) => {
    if (filters.filter === 'active' && (note.isArchived || note.isTrashed)) return false;
    if (filters.filter === 'archived' && !note.isArchived) return false;
    if (filters.filter === 'trashed' && !note.isTrashed) return false;
    if (filters.colorArgb != null && !noteColorsMatch(note.color, filters.colorArgb)) return false;
    if (filters.labelName && !note.labels.some((l) => l.name === filters.labelName)) return false;
    if (!query) return true;
    const inTitle = note.title.toLowerCase().includes(query);
    const inContent = note.content.toLowerCase().includes(query);
    const inChecklist = note.checklist.some((item) => item.text.toLowerCase().includes(query));
    return inTitle || inContent || inChecklist;
  });

  const pinned = result.filter((n) => n.isPinned);
  const unpinned = result.filter((n) => !n.isPinned);

  switch (filters.sortOrder) {
    case 'newest':
      result = [
        ...pinned.sort((a, b) => b.timestamp - a.timestamp),
        ...unpinned.sort((a, b) => b.timestamp - a.timestamp),
      ];
      break;
    case 'oldest':
      result = [
        ...pinned.sort((a, b) => a.timestamp - b.timestamp),
        ...unpinned.sort((a, b) => a.timestamp - b.timestamp),
      ];
      break;
    case 'manual':
    default:
      result = [
        ...pinned.sort((a, b) => a.position - b.position || b.timestamp - a.timestamp),
        ...unpinned.sort((a, c) => a.position - c.position || c.timestamp - a.timestamp),
      ];
  }

  return result;
}

export function shouldApplyRemoteNote(local: Note, remote: Note): boolean {
  if (local.isLocked) return false;
  return remote.timestamp >= local.timestamp;
}
