import { ColorSwatchRow } from '@/components/layout/ColorSwatch';
import { ChevronRightIcon, SortIcon } from '@/components/icons/Icons';
import type { Label } from '@/types/label';
import type { ReactNode } from 'react';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import { useOverflowHint } from '@/hooks/useOverflowHint';

/**
 * The cue that a filter row continues off-screen, shown only while it does.
 *
 * The first attempt was a gradient, which is the usual idiom — and it was the wrong one here: on
 * the dark and AMOLED themes it is a black fade over a black surface, so the cue that was added to
 * make hidden colours discoverable was itself undiscoverable. (Screenshot evidence:
 * /tmp/ui-after/filters-cue-390.png in the audit pass that produced this.) A button is legible on
 * every theme, and it also does something: pressing it scrolls the row, which is what a user who
 * noticed the clipped chips was trying to do anyway.
 *
 * Honours prefers-reduced-motion for the scroll it triggers.
 */
function ScrollCue({ target, visible }: { target: React.RefObject<HTMLElement | null>; visible: boolean }) {
  if (!visible) return null;
  return (
    <button
      type="button"
      aria-label="Scroll filters right"
      onClick={() => {
        const el = target.current;
        if (!el) return;
        const reduced =
          typeof globalThis.matchMedia === 'function' &&
          globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches;
        el.scrollBy({ left: Math.max(120, el.clientWidth * 0.6), behavior: reduced ? 'auto' : 'smooth' });
      }}
      className={`absolute inset-y-0 right-0 z-10 my-auto flex size-7 items-center justify-center rounded-full border border-brand-outline/30 bg-true-surface-variant text-brand-muted shadow-sm hover:text-brand-primary ${CHROME_FOCUS}`}
    >
      <ChevronRightIcon size={16} />
    </button>
  );
}

interface FilterChipProps {
  label: string;
  selected?: boolean;
  onClick?: () => void;
  disabled?: boolean;
  leading?: ReactNode;
  trailing?: ReactNode;
  compact?: boolean;
  /** Toggle chips only. Action chips (Clear, sort cycle) omit this. */
  pressed?: boolean;
  ariaLabel?: string;
  title?: string;
}

function FilterChip({
  label,
  selected = false,
  onClick,
  disabled = false,
  leading,
  trailing,
  compact = false,
  pressed,
  ariaLabel,
  title,
}: FilterChipProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-pressed={pressed}
      aria-label={ariaLabel}
      title={title}
      className={`filter-chip shrink-0 gap-1.5 ${CHROME_FOCUS} ${compact ? 'px-3 text-xs sm:px-3.5' : ''} ${
        selected ? 'filter-chip-active' : 'filter-chip-inactive'
      } ${disabled ? 'cursor-default opacity-70' : 'cursor-pointer'}`}
    >
      {leading}
      <span className="whitespace-nowrap">{label}</span>
      {trailing}
    </button>
  );
}

const SORT_LABELS = {
  manual: 'Manual',
  newest: 'Newest',
  oldest: 'Oldest',
} as const;

interface FilterRowProps {
  sortOrder: 'manual' | 'newest' | 'oldest';
  onSortOrderCycle: () => void;
  /** When true, search overrides sort order (D14 relevance) — sort chip is disabled. */
  sortDisabled?: boolean;
  selectedColor: number | null;
  onColorSelect: (color: number | null) => void;
  labels: Label[];
  selectedLabelName: string | null;
  onLabelSelect: (name: string | null) => void;
  hasActiveFilters: boolean;
  onClearFilters: () => void;
}

export function FilterRow({
  sortOrder,
  onSortOrderCycle,
  sortDisabled = false,
  selectedColor,
  onColorSelect,
  labels,
  selectedLabelName,
  onLabelSelect,
  hasActiveFilters,
  onClearFilters,
}: FilterRowProps) {
  const sortLabel = sortDisabled ? 'Relevance' : SORT_LABELS[sortOrder];
  const colorRow = useOverflowHint<HTMLDivElement>();
  const labelRow = useOverflowHint<HTMLDivElement>();

  return (
    <div className="flex flex-col gap-1.5 pb-2">
      {/* tabIndex: an overflow container has to be reachable by keyboard or its hidden content is
          unreachable without a pointer (WCAG 2.1.1). Without it, everything off-screen in these
          rows — several colours, and every label past the first few on a phone — could only be got
          at by swiping. */}
      <div className="relative">
        <div
          ref={colorRow.ref}
          tabIndex={0}
          role="group"
          aria-label="Filters"
          className={`flex items-center gap-2.5 overflow-x-auto px-shell py-1.5 scrollbar-none ${CHROME_FOCUS}`}
        >
          <FilterChip
            compact
            label={sortLabel}
            selected={true}
            onClick={onSortOrderCycle}
            disabled={sortDisabled}
            ariaLabel={
              sortDisabled
                ? 'Sort locked to relevance while searching'
                : `Sort by ${SORT_LABELS[sortOrder]}. Tap to change`
            }
            title={sortDisabled ? 'Relevance while searching' : 'Tap to change sort'}
            leading={<SortIcon size={14} />}
            trailing={
              sortDisabled ? null : (
                <ChevronRightIcon
                  size={14}
                  className="rotate-90 opacity-70"
                />
              )
            }
          />

          {hasActiveFilters ? (
            <FilterChip compact label="Clear" selected onClick={onClearFilters} />
          ) : null}

          <div
            className="flex min-h-9 min-w-0 items-center"
            role="group"
            aria-label="Color filter"
          >
            <ColorSwatchRow
              selectedColor={selectedColor}
              onSelect={onColorSelect}
              allSelected={selectedColor === null}
              onSelectAll={() => onColorSelect(null)}
            />
          </div>
        </div>
        <ScrollCue target={colorRow.ref} visible={colorRow.canScrollRight} />
      </div>

      {labels.length > 0 ? (
        <div className="relative">
          <div
            ref={labelRow.ref}
            tabIndex={0}
            role="group"
            aria-label="Label filter"
            className={`flex gap-1.5 overflow-x-auto px-shell py-0.5 scrollbar-none ${CHROME_FOCUS}`}
          >
            <FilterChip
              compact
              label="All labels"
              selected={selectedLabelName === null}
              pressed={selectedLabelName === null}
              onClick={() => onLabelSelect(null)}
            />
            {labels.map((label) => (
              <FilterChip
                key={label.id}
                compact
                label={label.name}
                selected={selectedLabelName === label.name}
                pressed={selectedLabelName === label.name}
                onClick={() =>
                  onLabelSelect(selectedLabelName === label.name ? null : label.name)
                }
              />
            ))}
          </div>
          <ScrollCue target={labelRow.ref} visible={labelRow.canScrollRight} />
        </div>
      ) : null}
    </div>
  );
}
