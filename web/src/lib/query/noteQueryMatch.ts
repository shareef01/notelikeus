import { NOTE_COLOR_OPTIONS } from '@/theme/colors';
import type { Note } from '@/types/note';
import type { NoteFlag, NoteScope, ParsedQuery } from '@/lib/query/noteQueryParser';

/**
 * Keywords for `color:`, in `NOTE_COLOR_OPTIONS` order: Kotlin `NOTE_COLOR_KEYWORDS`.
 *
 * Not the display names (`NOTE_COLOR_NAMES`): these are what someone types, and they are the same
 * words on every platform so a query written on Android works here.
 */
export const NOTE_COLOR_KEYWORDS = [
  'none', 'red', 'orange', 'yellow', 'green', 'teal', 'blue', 'purple', 'pink',
] as const;

/**
 * What a parsed query narrows to once its names are resolved, as Kotlin `MainViewModel.applyInputs`
 * resolves them.
 *
 * A label name that matches no label is dropped, and so is a colour keyword that is not a colour —
 * the same as native, where they resolve to nothing and narrow nothing.
 */
export interface ResolvedOperators {
  text: string;
  /** Lower-cased label names; a note matches if it has any of them. */
  labelNames: Set<string>;
  /** Stored ARGB values, light and dark variants both; a note matches any of them. */
  colors: Set<number>;
  flags: NoteFlag[];
  scope: NoteScope | null;
  /** [after, before) on the edited timestamp, or null. */
  dateRange: { from: number; to: number } | null;
  unknown: string[];
}

export function resolveOperators(parsed: ParsedQuery, knownLabelNames: Iterable<string>): ResolvedOperators {
  const known = new Set<string>();
  for (const name of knownLabelNames) known.add(name.toLowerCase());
  const labelNames = new Set(
    parsed.labelNames.map((name) => name.toLowerCase()).filter((name) => known.has(name)),
  );

  const colors = new Set<number>();
  for (const keyword of parsed.colorNames) {
    const index = (NOTE_COLOR_KEYWORDS as readonly string[]).indexOf(keyword);
    const option = index >= 0 ? NOTE_COLOR_OPTIONS[index] : undefined;
    if (option) {
      colors.add(option.light);
      colors.add(option.dark);
    }
  }

  const dateRange =
    parsed.after == null && parsed.before == null
      ? null
      : { from: parsed.after ?? -Infinity, to: parsed.before ?? Infinity };

  return {
    text: parsed.text,
    labelNames,
    colors,
    flags: parsed.flags,
    scope: parsed.scope,
    dateRange,
    unknown: parsed.unknown,
  };
}

/** A cheap scheme check, as Kotlin `containsLink`. */
function containsLink(note: Note): boolean {
  const haystack = `${note.title}\n${note.content}`.toLowerCase();
  return haystack.includes('http://') || haystack.includes('https://') || haystack.includes('www.');
}

/** Kotlin `Note.satisfies`. */
export function noteSatisfies(note: Note, flag: NoteFlag, now: number): boolean {
  switch (flag) {
    case 'PINNED': return note.isPinned;
    case 'HAS_REMINDER': return note.reminderTimestamp != null;
    case 'REMINDER_OVERDUE': return note.reminderTimestamp != null && note.reminderTimestamp <= now;
    case 'HAS_CHECKLIST': return note.checklist.length > 0;
    case 'HAS_UNCHECKED_ITEMS': return note.checklist.some((item) => !item.isChecked);
    case 'HAS_LINKS': return containsLink(note);
    case 'UNTITLED': return note.title.trim() === '';
    case 'UNLABELED': return note.labels.length === 0;
  }
}

/** Kotlin `NoteQueryMatcher.matchesScope`. */
export function noteInScope(note: Note, scope: NoteScope): boolean {
  switch (scope) {
    case 'ACTIVE': return !note.isArchived && !note.isTrashed;
    case 'ARCHIVE': return note.isArchived && !note.isTrashed;
    case 'TRASH': return note.isTrashed;
    case 'ALL': return true;
  }
}

/** Every narrowing dimension the operators add, except scope and free text. */
export function matchesOperators(note: Note, ops: ResolvedOperators, now: number): boolean {
  if (ops.colors.size > 0 && !ops.colors.has(note.color)) return false;
  if (ops.labelNames.size > 0 && !note.labels.some((label) => ops.labelNames.has(label.name.toLowerCase()))) {
    return false;
  }
  if (!ops.flags.every((flag) => noteSatisfies(note, flag, now))) return false;
  if (ops.dateRange && !(note.timestamp >= ops.dateRange.from && note.timestamp < ops.dateRange.to)) {
    return false;
  }
  return true;
}
