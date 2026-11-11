import { noteColorCounterpart } from '@/theme/colors';
import type { DateField, NoteFlag } from '@/lib/query/noteQueryParser';

export type { DateField, NoteFlag } from '@/lib/query/noteQueryParser';

/** How a multi-label selection combines: Kotlin `LabelMatch`. */
export type LabelMatch = 'ANY' | 'ALL';

/**
 * Inclusive-start, exclusive-end epoch-millis window: Kotlin `DateRange`.
 *
 * An open end is a bound too far out to reach, not a nullable field, as on native. Safe integers
 * rather than ±Infinity because a saved filter is JSON, and JSON turns Infinity into null.
 */
export interface DateRange {
  from: number;
  to: number;
}

export const OPEN_START = Number.MIN_SAFE_INTEGER;
export const OPEN_END = Number.MAX_SAFE_INTEGER;

export function dateRangeContains(range: DateRange, timestamp: number): boolean {
  return timestamp >= range.from && timestamp < range.to;
}

/** Every flag, in the order the Filters sheet lists them (Kotlin `NoteFlag.entries`). */
export const NOTE_FLAGS: readonly NoteFlag[] = [
  'PINNED',
  'HAS_REMINDER',
  'REMINDER_OVERDUE',
  'HAS_CHECKLIST',
  'HAS_UNCHECKED_ITEMS',
  'HAS_LINKS',
  'UNTITLED',
  'UNLABELED',
];

/** Native `flag_*` strings. */
export const NOTE_FLAG_LABELS: Record<NoteFlag, string> = {
  PINNED: 'Pinned',
  HAS_REMINDER: 'Has reminder',
  REMINDER_OVERDUE: 'Overdue',
  HAS_CHECKLIST: 'Has checklist',
  HAS_UNCHECKED_ITEMS: 'Unfinished items',
  HAS_LINKS: 'Has links',
  UNTITLED: 'Untitled',
  UNLABELED: 'No labels',
};

export const DATE_FIELDS: readonly DateField[] = ['CREATED', 'EDITED', 'REMINDER'];

/** Native `date_field_*` strings. */
export const DATE_FIELD_LABELS: Record<DateField, string> = {
  CREATED: 'Created',
  EDITED: 'Edited',
  REMINDER: 'Reminder',
};

/** Kotlin `DatePreset`: a window from the start of a day some days back, open-ended forward. */
export type DatePreset = 'ANY' | 'TODAY' | 'LAST_7' | 'LAST_30';

export const DATE_PRESETS: readonly DatePreset[] = ['ANY', 'TODAY', 'LAST_7', 'LAST_30'];

export const DATE_PRESET_LABELS: Record<DatePreset, string> = {
  ANY: 'Any time',
  TODAY: 'Today',
  LAST_7: 'Last 7 days',
  LAST_30: 'Last 30 days',
};

const DATE_PRESET_DAYS: Record<DatePreset, number | null> = {
  ANY: null,
  TODAY: 0,
  LAST_7: 7,
  LAST_30: 30,
};

/** Local midnight [days] days before the day containing [now]. */
function localDayStart(now: number, days: number): number {
  const date = new Date(now);
  date.setHours(0, 0, 0, 0);
  date.setDate(date.getDate() - days);
  return date.getTime();
}

/** The window a preset stands for, as of [now]; `ANY` is no window at all. */
export function datePresetRange(preset: DatePreset, now: number): DateRange | null {
  const days = DATE_PRESET_DAYS[preset];
  return days == null ? null : { from: localDayStart(now, days), to: OPEN_END };
}

/** Which preset the range is, if it is one, so the sheet can mark it. */
export function datePresetOf(range: DateRange | null | undefined, now: number): DatePreset | null {
  for (const preset of DATE_PRESETS) {
    const candidate = datePresetRange(preset, now);
    if (candidate == null ? range == null : range != null && sameRange(range, candidate)) return preset;
  }
  return null;
}

function sameRange(a: DateRange | null | undefined, b: DateRange | null | undefined): boolean {
  if (a == null || b == null) return a == null && b == null;
  return a.from === b.from && a.to === b.to;
}

/**
 * The narrowing half of what is on screen: which notes, not how they are shown.
 *
 * Kotlin `NoteQuery.narrowingOnly()`. Scope and the search text are part of it (a saved filter
 * restores both), sort is not: restoring "Invoices" should not change a preference the user did
 * not touch.
 */
export interface NarrowingFilters {
  filter: 'active' | 'archived' | 'trashed';
  searchQuery: string;
  /** Stored ARGB values, a hue's light and dark variant together. */
  colors: number[];
  /** Label names, as web identifies labels. */
  labelNames: string[];
  labelMatch: LabelMatch;
  flags: NoteFlag[];
  dateField: DateField;
  dateRange: DateRange | null;
}

/** The fields {@link NarrowingFilters} adds to the filter row's, all optional for callers. */
export interface FilterSets {
  filter: NarrowingFilters['filter'];
  searchQuery?: string;
  colors?: number[];
  labelNames?: string[];
  labelMatch?: LabelMatch;
  flags?: NoteFlag[];
  dateField?: DateField;
  dateRange?: DateRange | null;
}

export const DEFAULT_NARROWING: NarrowingFilters = {
  filter: 'active',
  searchQuery: '',
  colors: [],
  labelNames: [],
  labelMatch: 'ANY',
  flags: [],
  dateField: 'EDITED',
  dateRange: null,
};

/** Fills in every optional field, so two filter states can be compared exactly. */
export function narrowingOf(filters: FilterSets): NarrowingFilters {
  return {
    filter: filters.filter,
    searchQuery: filters.searchQuery ?? '',
    colors: filters.colors ?? [],
    labelNames: filters.labelNames ?? [],
    labelMatch: filters.labelMatch ?? 'ANY',
    flags: filters.flags ?? [],
    dateField: filters.dateField ?? 'EDITED',
    dateRange: filters.dateRange ?? null,
  };
}

function sameSet<T>(a: readonly T[], b: readonly T[]): boolean {
  if (a.length !== b.length) return false;
  const set = new Set(a);
  return b.every((value) => set.has(value));
}

/**
 * Whether two states pick out the same notes. Sets compare as sets, so the order chips were
 * tapped in does not make a saved filter look different from what is on screen.
 */
export function narrowingEqual(a: FilterSets, b: FilterSets): boolean {
  const x = narrowingOf(a);
  const y = narrowingOf(b);
  return (
    x.filter === y.filter &&
    x.searchQuery.trim() === y.searchQuery.trim() &&
    sameSet(x.colors, y.colors) &&
    sameSet(x.labelNames, y.labelNames) &&
    x.labelMatch === y.labelMatch &&
    sameSet(x.flags, y.flags) &&
    x.dateField === y.dateField &&
    sameRange(x.dateRange, y.dateRange)
  );
}

/**
 * Kotlin `NoteQuery.hasActiveFilters`: whether anything narrows the list.
 *
 * Scope, sort, the date field and label matching are left out on purpose: being in Trash, or set
 * to "all of these" with no labels picked, is not "filtered".
 */
export function hasActiveFilters(filters: FilterSets): boolean {
  return (
    (filters.searchQuery ?? '').trim() !== '' ||
    (filters.colors?.length ?? 0) > 0 ||
    (filters.labelNames?.length ?? 0) > 0 ||
    (filters.flags?.length ?? 0) > 0 ||
    filters.dateRange != null
  );
}

/** How many narrowing dimensions the sheet holds, for its chip's count. Text is not one of them. */
export function sheetFilterCount(filters: FilterSets): number {
  return (
    (filters.colors?.length ? new Set(filters.colors.map(colorKey)).size : 0) +
    (filters.labelNames?.length ?? 0) +
    (filters.flags?.length ?? 0) +
    (filters.dateRange != null ? 1 : 0)
  );
}

/**
 * Kotlin `NoteQuery.cleared()`: drops every narrowing dimension, keeping where you are and how
 * you look at it — the scope, and the date field and label match the user chose.
 */
export function clearedFilters(): Pick<
  NarrowingFilters,
  'searchQuery' | 'colors' | 'labelNames' | 'flags' | 'dateRange'
> {
  return { searchQuery: '', colors: [], labelNames: [], flags: [], dateRange: null };
}

/** One identity per hue: the light variant, whichever variant was passed. */
function colorKey(argb: number): number {
  const counterpart = noteColorCounterpart(argb);
  return counterpart == null ? argb : Math.min(argb, counterpart);
}

/** Toggles one colour, carrying its light/dark counterpart with it: Kotlin `toggleColor`. */
export function toggleColor(colors: readonly number[], argb: number): number[] {
  const counterpart = noteColorCounterpart(argb);
  const siblings = counterpart != null && counterpart !== argb ? [argb, counterpart] : [argb];
  if (colors.includes(argb) || (counterpart != null && colors.includes(counterpart))) {
    return colors.filter((value) => !siblings.includes(value));
  }
  return [...colors, ...siblings.filter((value) => !colors.includes(value))];
}

export function colorSelected(colors: readonly number[], argb: number): boolean {
  if (colors.includes(argb)) return true;
  const counterpart = noteColorCounterpart(argb);
  return counterpart != null && colors.includes(counterpart);
}

export function toggleValue<T>(values: readonly T[], value: T): T[] {
  return values.includes(value) ? values.filter((entry) => entry !== value) : [...values, value];
}

/**
 * The colour a new note should take from the filters: the chosen hue when exactly one is chosen,
 * as the single colour filter did before NL-04, and none when several are.
 */
export function singleFilterColor(colors: readonly number[] | undefined): number | null {
  if (!colors || colors.length === 0) return null;
  const first = colors[0]!;
  const counterpart = noteColorCounterpart(first);
  return colors.every((value) => value === first || value === counterpart) ? first : null;
}
