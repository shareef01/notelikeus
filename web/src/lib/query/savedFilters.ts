import {
  NOTE_FLAGS,
  narrowingOf,
  type DateField,
  type FilterSets,
  type NarrowingFilters,
} from '@/lib/query/noteQuery';

/**
 * A filter the user named and kept: Kotlin `SavedFilter`.
 *
 * Device-local, like native's DataStore copy: a lens on the notes, not the notes, so it lives in
 * the UI preferences and never syncs. The name is the identity, so saving over a name replaces it.
 */
export interface SavedFilter {
  name: string;
  query: NarrowingFilters;
}

/** Long enough to be descriptive, short enough for a drawer row (Kotlin `MAX_NAME_LENGTH`). */
export const SAVED_FILTER_MAX_NAME_LENGTH = 40;

/** A cap, because this is unbounded user input in a preferences blob (Kotlin `MAX_SAVED`). */
export const SAVED_FILTER_MAX = 20;

/** The filter already saved under [name], compared as native does: ignoring case. */
export function findSavedFilter(list: readonly SavedFilter[], name: string): SavedFilter | undefined {
  const key = name.trim().toLowerCase();
  return list.find((filter) => filter.name.toLowerCase() === key);
}

/**
 * Kotlin `SettingsRepositoryImpl.saveFilter`: the new filter goes to the top, replaces one with
 * the same name, and the list keeps the newest {@link SAVED_FILTER_MAX}, so a full list drops the
 * oldest. Sort is not stored (`narrowingOnly()`): a shortcut names a set of notes, not a way of
 * looking at them.
 */
export function withSavedFilter(
  list: readonly SavedFilter[],
  name: string,
  filters: FilterSets,
): SavedFilter[] {
  const trimmed = name.trim().slice(0, SAVED_FILTER_MAX_NAME_LENGTH).trim();
  if (trimmed === '') return [...list];
  const query = narrowingOf(filters);
  const entry: SavedFilter = {
    name: trimmed,
    query: {
      ...query,
      searchQuery: query.searchQuery.trim(),
      colors: [...query.colors],
      labelNames: [...query.labelNames],
      flags: [...query.flags],
      dateRange: query.dateRange ? { ...query.dateRange } : null,
    },
  };
  const key = trimmed.toLowerCase();
  return [entry, ...list.filter((filter) => filter.name.toLowerCase() !== key)].slice(0, SAVED_FILTER_MAX);
}

/** Kotlin `deleteSavedFilter`: by exact name, the one the row shows. */
export function withoutSavedFilter(list: readonly SavedFilter[], name: string): SavedFilter[] {
  return list.filter((filter) => filter.name !== name);
}

const FILTER_SCOPES = new Set(['active', 'archived', 'trashed']);
const DATE_FIELD_SET = new Set<DateField>(['CREATED', 'EDITED', 'REMINDER']);
const FLAG_SET = new Set<string>(NOTE_FLAGS);

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/**
 * Reads what storage holds, dropping anything that is not a saved filter.
 *
 * localStorage is writable by anything on the origin and by older builds; a malformed entry costs
 * one shortcut, where trusting it would put a shape the matcher does not expect into the query.
 */
export function sanitizeSavedFilters(raw: unknown): SavedFilter[] {
  if (!Array.isArray(raw)) return [];
  const result: SavedFilter[] = [];
  const seen = new Set<string>();
  for (const item of raw) {
    if (result.length >= SAVED_FILTER_MAX) break;
    if (typeof item !== 'object' || item === null) continue;
    const { name, query } = item as { name?: unknown; query?: unknown };
    if (typeof name !== 'string' || typeof query !== 'object' || query === null) continue;
    const trimmed = name.trim().slice(0, SAVED_FILTER_MAX_NAME_LENGTH).trim();
    if (trimmed === '' || seen.has(trimmed.toLowerCase())) continue;
    const q = query as Record<string, unknown>;
    if (typeof q.filter !== 'string' || !FILTER_SCOPES.has(q.filter)) continue;
    const range = q.dateRange as { from?: unknown; to?: unknown } | null | undefined;
    seen.add(trimmed.toLowerCase());
    result.push({
      name: trimmed,
      query: narrowingOf({
        filter: q.filter as NarrowingFilters['filter'],
        searchQuery: typeof q.searchQuery === 'string' ? q.searchQuery : '',
        colors: Array.isArray(q.colors) ? q.colors.filter(Number.isInteger) : [],
        labelNames: Array.isArray(q.labelNames)
          ? q.labelNames.filter((value): value is string => typeof value === 'string')
          : [],
        labelMatch: q.labelMatch === 'ALL' ? 'ALL' : 'ANY',
        flags: Array.isArray(q.flags)
          ? (q.flags.filter((value) => typeof value === 'string' && FLAG_SET.has(value)) as NarrowingFilters['flags'])
          : [],
        dateField: DATE_FIELD_SET.has(q.dateField as DateField) ? (q.dateField as DateField) : 'EDITED',
        dateRange:
          range && isFiniteNumber(range.from) && isFiniteNumber(range.to)
            ? { from: range.from, to: range.to }
            : null,
      }),
    });
  }
  return result;
}
