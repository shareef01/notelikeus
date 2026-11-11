import type { Note } from '@/types/note';
import { noteInScope, noteSatisfies } from '@/lib/query/noteQueryMatch';
import type { NoteFlag } from '@/lib/query/noteQueryParser';
import { hasActiveFilters, type FilterSets } from '@/lib/query/noteQuery';

export type SmartViewId = 'reminders' | 'unfinished' | 'unlabeled';

/**
 * Kotlin `SmartView`: a destination in the drawer, defined by one flag over active notes.
 *
 * On web a view is the search it stands for, typed into the box: choosing "Reminders" shows
 * `has:reminder`, so the box says what the list is and teaches the syntax that produced it.
 */
export interface SmartView {
  id: SmartViewId;
  label: string;
  query: string;
  flag: NoteFlag;
}

/** Labels match native `view_reminders`, `view_unfinished` and `view_unlabeled`. */
export const SMART_VIEWS: readonly SmartView[] = [
  { id: 'reminders', label: 'Reminders', query: 'has:reminder', flag: 'HAS_REMINDER' },
  { id: 'unfinished', label: 'Unfinished', query: 'has:unchecked', flag: 'HAS_UNCHECKED_ITEMS' },
  { id: 'unlabeled', label: 'Unlabelled', query: 'is:unlabeled', flag: 'UNLABELED' },
];

/** Active notes in each view, for the drawer's counts. */
export function smartViewCounts(notes: Note[], now: number = Date.now()): Record<SmartViewId, number> {
  const counts: Record<SmartViewId, number> = { reminders: 0, unfinished: 0, unlabeled: 0 };
  for (const note of notes) {
    if (!noteInScope(note, 'ACTIVE')) continue;
    for (const view of SMART_VIEWS) if (noteSatisfies(note, view.flag, now)) counts[view.id]++;
  }
  return counts;
}

/** Which view is exactly what is on screen: applying it would change nothing. */
export function activeSmartView(filters: FilterSets): SmartViewId | null {
  if (filters.filter !== 'active') return null;
  // The view is its query alone: anything the Filters sheet adds makes it a different list.
  if (hasActiveFilters({ ...filters, searchQuery: '' })) return null;
  const query = (filters.searchQuery ?? '').trim();
  return SMART_VIEWS.find((view) => view.query === query)?.id ?? null;
}
