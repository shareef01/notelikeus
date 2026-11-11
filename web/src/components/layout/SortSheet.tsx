import { ResponsiveSheet } from '@/components/layout/ResponsiveSheet';
import { CheckIcon, SortIcon } from '@/components/icons/Icons';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';

export type SortOrder = 'manual' | 'newest' | 'oldest';

export const SORT_OPTION_LABELS: Record<SortOrder, string> = {
  manual: 'Manual',
  newest: 'Newest first',
  oldest: 'Oldest first',
};

const SORT_OPTION_HINTS: Record<SortOrder, string> = {
  manual: 'Arrange notes yourself with Move up and Move down',
  newest: 'Most recently created first',
  oldest: 'Earliest created first',
};

/**
 * Every order native offers, manual first as it is the default (NL-05). Manual was left out while
 * the web client had no way to move a note (F18); cards and the selection bar now have Move up and
 * Move down, and list view a drag handle.
 */
export const SORT_OPTIONS: SortOrder[] = ['manual', 'newest', 'oldest'];

/** Why Manual cannot reorder right now, when a filter is the reason (native hides the handle). */
export const REORDER_FILTERED_HINT = 'Clear the search and filters to move notes';

interface SortSheetProps {
  open: boolean;
  onClose: () => void;
  sortOrder: SortOrder;
  onSelect: (order: SortOrder) => void;
  /**
   * A search or filter is narrowing the list, so notes cannot be moved even under Manual: position
   * is a property of the whole list. Said under the Manual option instead of leaving it to fail.
   */
  reorderBlockedByFilters?: boolean;
}

/**
 * Sort choice, replacing a control that cycled Manual → Newest → Oldest on every tap.
 *
 * The cycle had one problem that no amount of styling fixes: the destination was not visible before
 * the action. A user who wanted "Oldest first" had to press once, read the toast, and press again if
 * they had guessed wrong — and from the far side of the list the control looked identical either
 * way. Here all three destinations are listed, the active one is marked, and nothing changes until
 * something is chosen.
 *
 * Built on ResponsiveSheet, which is this codebase's existing pattern for a short list of choices:
 * a bottom sheet on a phone, a centred modal above `md`, with the focus trap and Escape handling
 * that live in that component rather than being reimplemented here.
 *
 * `aria-pressed` rather than `role="radio"`, matching the filter chips these sit beside — the whole
 * group is reachable by Tab and each option announces its own state.
 */
export function SortSheet({
  open,
  onClose,
  sortOrder,
  onSelect,
  reorderBlockedByFilters = false,
}: SortSheetProps) {
  return (
    <ResponsiveSheet open={open} onClose={onClose} ariaLabel="Sort notes" maxWidthClass="md:max-w-sm">
      <div className="px-5 pb-2 pt-1 sm:px-6">
        <h2 className="flex items-center gap-2 text-base font-semibold text-brand-primary">
          <SortIcon size={18} />
          Sort notes
        </h2>
      </div>
      <div className="px-2 pb-safe-action sm:px-3">
        {SORT_OPTIONS.map((option) => {
          const active = option === sortOrder;
          return (
            <button
              key={option}
              type="button"
              aria-pressed={active}
              onClick={() => {
                onSelect(option);
                onClose();
              }}
              className={`flex w-full items-center gap-3 rounded-note px-3 py-3 text-left ${CHROME_FOCUS} ${
                active ? 'bg-true-surface-variant' : 'hover:bg-true-surface-variant/60'
              }`}
            >
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-brand-primary">
                  {SORT_OPTION_LABELS[option]}
                </span>
                <span className="block text-xs text-brand-muted">{SORT_OPTION_HINTS[option]}</span>
                {option === 'manual' && reorderBlockedByFilters ? (
                  <span className="mt-0.5 block text-xs font-medium text-brand-muted">
                    {REORDER_FILTERED_HINT}
                  </span>
                ) : null}
              </span>
              {active ? (
                <CheckIcon size={18} className="shrink-0 text-brand-accent" />
              ) : null}
            </button>
          );
        })}
      </div>
    </ResponsiveSheet>
  );
}
