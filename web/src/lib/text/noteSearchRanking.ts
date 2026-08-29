import { buildSearchText, foldForSearch, searchTokens } from '@/lib/text/searchText';
import type { Note } from '@/types/note';

/**
 * How well a note answers a text query, and what to do when nothing answers it at all.
 *
 * Port of Kotlin `NoteSearchRanking`. Matching stays exact (`noteMatchesSearchQuery`); this
 * decides order, and the near-miss fallback when the exact pass is empty.
 */

const TITLE_EXACT = 1_000;
const TITLE_PREFIX = 400;
const LABEL = 200;
const CHECKLIST = 120;
const BODY = 100;

/** Tokens shorter than this are not worth correcting: every short word is 2 edits from many. */
const MIN_FUZZY_LENGTH = 4;
const MAX_EDITS = 2;

function containsTokenPrefix(haystack: string, needle: string): boolean {
  return haystack.startsWith(needle) || haystack.includes(` ${needle}`);
}

function searchableText(note: Note): string {
  return buildSearchText(
    note.title,
    note.content,
    note.checklist.map((item) => item.text),
    note.labels.map((label) => label.name),
  );
}

function isCloseTo(token: string, needle: string): boolean {
  if (token.startsWith(needle)) return true;
  if (needle.length < MIN_FUZZY_LENGTH) return false;
  return editDistanceWithin(token, needle, MAX_EDITS);
}

/**
 * A relevance score for `note` against already-folded `needles`.
 *
 * Scores are summed across tokens, so a two-word query matching both in the title beats one
 * matching one word in the title and one in the body.
 */
export function scoreNote(note: Note, needles: string[]): number {
  if (needles.length === 0) return 0;

  const title = foldForSearch(note.title);
  let labels: string | undefined;
  let checklist: string | undefined;
  let body: string | undefined;

  let total = title === needles.join(' ') ? TITLE_EXACT : 0;

  for (const needle of needles) {
    if (containsTokenPrefix(title, needle)) {
      total += TITLE_PREFIX;
      continue;
    }
    labels ??= note.labels.map((label) => foldForSearch(label.name)).join(' ');
    if (containsTokenPrefix(labels, needle)) {
      total += LABEL;
      continue;
    }
    checklist ??= note.checklist.map((item) => foldForSearch(item.text)).join(' ');
    if (containsTokenPrefix(checklist, needle)) {
      total += CHECKLIST;
      continue;
    }
    body ??= foldForSearch(note.content);
    if (containsTokenPrefix(body, needle)) total += BODY;
  }
  return total;
}

function compareScored(a: { note: Note; score: number }, b: { note: Note; score: number }): number {
  if (b.score !== a.score) return b.score - a.score;
  return b.note.timestamp - a.note.timestamp;
}

/**
 * Orders notes by relevance, then by most recently edited.
 *
 * Pinned notes still lead: that is what pinning means, and a pinned note dropping below an
 * unpinned one because it scored lower would read as the pin having been ignored.
 */
export function byRelevance(notes: Note[], text: string): Note[] {
  const needles = searchTokens(text);
  if (needles.length === 0) return notes;

  const scored = notes.map((note) => ({ note, score: scoreNote(note, needles) }));
  const pinned = scored.filter((entry) => entry.note.isPinned);
  pinned.sort(compareScored);
  const rest = scored.filter((entry) => !entry.note.isPinned);
  rest.sort(compareScored);
  return [...pinned, ...rest].map((entry) => entry.note);
}

/**
 * Notes whose text is close to the query, for when nothing matches it exactly.
 *
 * Tokens under MIN_FUZZY_LENGTH must match as a prefix. At three characters almost everything
 * is within two edits of almost everything else.
 */
export function fuzzyMatches(notes: Note[], text: string): Note[] {
  const needles = searchTokens(text);
  if (needles.length === 0) return [];
  return notes.filter((note) => {
    const haystack = searchTokens(searchableText(note));
    return needles.every((needle) => haystack.some((token) => isCloseTo(token, needle)));
  });
}

/** Whether `a` and `b` are within `max` single-character edits. Exported for tests. */
export function editDistanceWithin(a: string, b: string, max: number): boolean {
  if (a.length - b.length > max || b.length - a.length > max) return false;

  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
  let current = Array.from({ length: b.length + 1 }, () => 0);
  for (let i = 1; i <= a.length; i++) {
    current[0] = i;
    let rowMin = current[0];
    for (let j = 1; j <= b.length; j++) {
      const substitution = previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1);
      current[j] = Math.min(substitution, previous[j] + 1, current[j - 1] + 1);
      if (current[j] < rowMin) rowMin = current[j];
    }
    if (rowMin > max) return false;
    const swap = previous;
    previous = current;
    current = swap;
  }
  return previous[b.length] <= max;
}
