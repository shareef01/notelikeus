import { NOTE_COLOR_OPTIONS } from '@/theme/colors';
import type { Note } from '@/types/note';
import type { DateField, NoteFlag, NoteScope, ParsedQuery } from '@/lib/query/noteQueryParser';
import type { DateRange, FilterSets, LabelMatch } from '@/lib/query/noteQuery';

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
  /** `created:` / `edited:` / `reminder:`, when typed; replaces the sheet's choice. */
  dateField: DateField | null;
  /** [after, before), or null. */
  dateRange: DateRange | null;
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
    dateField: parsed.dateField,
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

const FILTER_SCOPE: Record<FilterSets['filter'], NoteScope> = {
  active: 'ACTIVE',
  archived: 'ARCHIVE',
  trashed: 'TRASH',
};

/**
 * Everything that decides which notes match, once the box's operators are folded into the
 * Filters sheet's choices: Kotlin `NoteQuery` as `MainViewModel.applyInputs` builds it.
 */
export interface EffectiveQuery {
  text: string;
  scope: NoteScope;
  colors: Set<number>;
  /** Lower-cased label names. */
  labelNames: Set<string>;
  labelMatch: LabelMatch;
  flags: Set<NoteFlag>;
  dateField: DateField;
  dateRange: DateRange | null;
}

/**
 * Folds typed operators into the sheet's filters the way native does: labels, colours and flags
 * add to the sheet's sets, while `in:`, a date field and a date range replace its choice.
 */
export function effectiveQuery(filters: FilterSets, ops: ResolvedOperators | null): EffectiveQuery {
  const labelNames = new Set((filters.labelNames ?? []).map((name) => name.toLowerCase()));
  const colors = new Set(filters.colors ?? []);
  const flags = new Set(filters.flags ?? []);
  if (ops) {
    for (const name of ops.labelNames) labelNames.add(name);
    for (const color of ops.colors) colors.add(color);
    for (const flag of ops.flags) flags.add(flag);
  }
  return {
    text: ops ? ops.text : (filters.searchQuery ?? ''),
    scope: ops?.scope ?? FILTER_SCOPE[filters.filter],
    colors,
    labelNames,
    labelMatch: filters.labelMatch ?? 'ANY',
    flags,
    dateField: ops?.dateField ?? filters.dateField ?? 'EDITED',
    dateRange: ops?.dateRange ?? filters.dateRange ?? null,
  };
}

function matchesLabels(note: Note, query: EffectiveQuery): boolean {
  if (query.labelNames.size === 0) return true;
  const own = new Set(note.labels.map((label) => label.name.toLowerCase()));
  if (query.labelMatch === 'ALL') {
    for (const name of query.labelNames) if (!own.has(name)) return false;
    return true;
  }
  for (const name of own) if (query.labelNames.has(name)) return true;
  return false;
}

function matchesDate(note: Note, query: EffectiveQuery): boolean {
  const range = query.dateRange;
  if (range == null) return true;
  // One timestamp per note, moved on edit, so Created and Edited read the same field, as native.
  const value = query.dateField === 'REMINDER' ? note.reminderTimestamp : note.timestamp;
  if (value == null) return false;
  return value >= range.from && value < range.to;
}

/** Kotlin `NoteQueryMatcher.matches`, every dimension but the free text. */
export function matchesQuery(note: Note, query: EffectiveQuery, now: number): boolean {
  if (!noteInScope(note, query.scope)) return false;
  if (query.colors.size > 0 && !query.colors.has(note.color)) return false;
  if (!matchesLabels(note, query)) return false;
  for (const flag of query.flags) if (!noteSatisfies(note, flag, now)) return false;
  return matchesDate(note, query);
}
