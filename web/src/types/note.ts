import type { ChecklistItem } from './checklist';
import type { Label } from './label';
import type { Attachment } from './attachment';
import { noteColorsMatch } from '@/theme/colors';
import { byRelevance, fuzzyMatches } from '@/lib/text/noteSearchRanking';
import { buildSearchText, noteMatchesSearchQuery, searchTokens } from '@/lib/text/searchText';

/**
 * Canonical note model — field names match Android Room and the cloud map.
 * `timestamp` is the client-set edit clock, shown to the user and used for display sort order.
 */
export interface Note {
  /** Note id (string form of Android localId). */
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
  reminderTimestamp: number | null;
  /**
   * Firestore's server-assigned commit time (epoch millis) as of the last time this device
   * observed a write to this note in the cloud. Null until the note has synced at least once
   * under this scheme. This — not `timestamp` — is what conflict resolution compares, since a
   * device's own clock can be wrong or spoofed; see notesRepository.ts's `mergeRemoteNotes` and
   * the shared Kotlin engine's NoteSyncEngine.kt.
   */
  serverUpdatedAt: number | null;
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

/**
 * Every note syncs. Kept as a named predicate because the sync paths read better for it and
 * it is the single place to reintroduce an exclusion if one is ever needed again.
 */
export function isCloudSyncEligible(_note: Note): boolean {
  return true;
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
    reminderTimestamp: null,
    serverUpdatedAt: null,
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
  return nextLocalNoteIdAfter(maxId);
}

/**
 * Same allocation rule as {@link allocateLocalNoteId}, but takes the current max directly
 * instead of rescanning a notes array — lets callers that allocate many ids in a row (e.g.
 * backup import) track a running max instead of an O(n) scan per id.
 */
export function nextLocalNoteIdAfter(maxId: number): number {
  // A bare Date.now() can collide when two tabs/windows each create their first note
  // within the same millisecond (both compute maxId=0 and the same candidate), silently
  // clobbering one note's cloud document. The random suffix makes that require the same
  // millisecond AND the same 1-in-1000 draw.
  const randomSuffix = Math.floor(Math.random() * 1000);
  const candidate = Date.now() * 1000 + randomSuffix;
  return Math.max(maxId + 1, candidate);
}

function matchesScopeAndFacets(note: Note, filters: NoteQueryFilters): boolean {
  if (filters.filter === 'active' && (note.isArchived || note.isTrashed)) return false;
  if (filters.filter === 'archived' && !note.isArchived) return false;
  if (filters.filter === 'trashed' && !note.isTrashed) return false;
  if (filters.colorArgb != null && !noteColorsMatch(note.color, filters.colorArgb)) return false;
  if (filters.labelName && !note.labels.some((l) => l.name === filters.labelName)) return false;
  return true;
}

function noteHaystack(note: Note): string {
  return buildSearchText(
    note.title,
    note.content,
    note.checklist.map((item) => item.text),
    note.labels.map((label) => label.name),
  );
}

function sortWithoutSearch(notes: Note[], sortOrder: NoteQueryFilters['sortOrder']): Note[] {
  const pinned = notes.filter((n) => n.isPinned);
  const unpinned = notes.filter((n) => !n.isPinned);

  switch (sortOrder) {
    case 'newest':
      return [
        ...pinned.sort((a, b) => b.timestamp - a.timestamp),
        ...unpinned.sort((a, b) => b.timestamp - a.timestamp),
      ];
    case 'oldest':
      return [
        ...pinned.sort((a, b) => a.timestamp - b.timestamp),
        ...unpinned.sort((a, b) => a.timestamp - b.timestamp),
      ];
    case 'manual':
    default:
      return [
        ...pinned.sort((a, b) => a.position - b.position || b.timestamp - a.timestamp),
        ...unpinned.sort((a, c) => a.position - c.position || c.timestamp - a.timestamp),
      ];
  }
}

export interface NoteSearchResult {
  notes: Note[];
  /** True when nothing matched exactly and these are near misses. The UI must say so. */
  isFuzzy: boolean;
}

/**
 * Match then order. Text queries rank by relevance (Kotlin `NoteQueryMatcher.search`); an empty
 * box keeps the chosen sort. A typo falls back to near matches without loosening colour/label/scope.
 */
export function searchNotes(notes: Note[], filters: NoteQueryFilters): NoteSearchResult {
  const scoped = notes.filter((note) => matchesScopeAndFacets(note, filters));
  const query = filters.searchQuery ?? '';
  const needles = searchTokens(query);
  if (needles.length === 0) {
    return { notes: sortWithoutSearch(scoped, filters.sortOrder), isFuzzy: false };
  }

  const strict = scoped.filter((note) => noteMatchesSearchQuery(noteHaystack(note), query));
  if (strict.length > 0) {
    return { notes: byRelevance(strict, query), isFuzzy: false };
  }

  const fuzzy = fuzzyMatches(scoped, query);
  return { notes: byRelevance(fuzzy, query), isFuzzy: fuzzy.length > 0 };
}

export function filterNotes(notes: Note[], filters: NoteQueryFilters): Note[] {
  return searchNotes(notes, filters).notes;
}
