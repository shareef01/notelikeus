import type { ChecklistItem } from './checklist';
import type { Label } from './label';
import type { Attachment } from './attachment';
import { NO_NOTE_COLOR } from '@/theme/colors';
import { byRelevance, fuzzyMatches } from '@/lib/text/noteSearchRanking';
import { buildSearchText, memoFolded, noteMatchesSearchTokens, searchTokens } from '@/lib/text/searchText';
import { localDayStartFrom, localDayStartOfDate, parseNoteQuery } from '@/lib/query/noteQueryParser';
import { effectiveQuery, matchesQuery, resolveOperators } from '@/lib/query/noteQueryMatch';
import type { FilterSets } from '@/lib/query/noteQuery';

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

/**
 * Which notes are on screen and in what order: Kotlin `NoteQuery`, minus the view mode.
 *
 * Colours, labels and flags are sets (NL-04), which is what lets "green or blue" and "tagged both
 * work and urgent" be expressed at all. Every narrowing field is optional so a caller can name only
 * the ones it sets; `narrowingOf` fills in the rest.
 */
export interface NoteQueryFilters extends FilterSets {
  filter: NoteFilter;
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
    color: partial.color ?? NO_NOTE_COLOR,
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

const EMPTY_STR_ARRAY: string[] = [];

function noteHaystack(note: Note): string {
  return memoFolded(note, 'haystack', () => {
    const checklistTexts =
      note.checklist.length > 0 ? note.checklist.map((item) => item.text) : EMPTY_STR_ARRAY;
    const labelNames =
      note.labels.length > 0 ? note.labels.map((label) => label.name) : EMPTY_STR_ARRAY;
    return buildSearchText(note.title, note.content, checklistTexts, labelNames);
  });
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
  /** The free text left once operators are taken out: what was searched for and what to highlight. */
  searchText: string;
  /** Operator-shaped tokens that were not understood (`labe:work`, `is:nonsense`). The UI says so. */
  unknownOperators: string[];
}

export interface SearchNotesOptions {
  /** Epoch millis, for `is:overdue`. Defaults to now. */
  now?: number;
  /** Every label name the user has, so `label:` resolves as native does. Defaults to the notes' labels. */
  knownLabelNames?: Iterable<string>;
}

function labelNamesOf(notes: Note[]): Set<string> {
  const names = new Set<string>();
  for (const note of notes) for (const label of note.labels) names.add(label.name);
  return names;
}

/**
 * Match then order. Text queries rank by relevance (Kotlin `NoteQueryMatcher.search`); an empty
 * box keeps the chosen sort. A typo falls back to near matches without loosening any other dimension.
 */
export function searchNotes(
  notes: Note[],
  filters: NoteQueryFilters,
  options: SearchNotesOptions = {},
): NoteSearchResult {
  const raw = filters.searchQuery ?? '';
  // Operators (`label:work is:pinned`) are taken out of the box first, as native does (NL-04). A
  // box without a colon has none, so the common case skips the parse.
  const now = options.now ?? Date.now();
  const ops = raw.includes(':')
    ? resolveOperators(
        parseNoteQuery(raw, localDayStartFrom(now), localDayStartOfDate),
        options.knownLabelNames ?? labelNamesOf(notes),
      )
    : null;
  const query = ops ? ops.text : raw;
  const unknownOperators = ops?.unknown ?? [];

  // `in:trash` / `is:archived` choose the pile, replacing the drawer's choice while they are typed;
  // typed labels, colours and flags add to the Filters sheet's, as native's `applyInputs` does.
  const effective = effectiveQuery(filters, ops);
  const scoped = notes.filter((note) => matchesQuery(note, effective, now));
  const needles = searchTokens(query);
  const searchText = query.trim();
  if (needles.length === 0) {
    return { notes: sortWithoutSearch(scoped, filters.sortOrder), isFuzzy: false, searchText, unknownOperators };
  }

  const strict = scoped.filter((note) => noteMatchesSearchTokens(noteHaystack(note), needles));
  if (strict.length > 0) {
    return { notes: byRelevance(strict, needles), isFuzzy: false, searchText, unknownOperators };
  }

  // A typo in the text falls back to near matches; the operators stay exact.
  const fuzzy = fuzzyMatches(scoped, needles);
  return { notes: byRelevance(fuzzy, needles), isFuzzy: fuzzy.length > 0, searchText, unknownOperators };
}

export function filterNotes(notes: Note[], filters: NoteQueryFilters): Note[] {
  return searchNotes(notes, filters).notes;
}
