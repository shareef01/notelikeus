import { ColorSwatchRow } from '@/components/layout/ColorSwatch';
import { ChevronRightIcon, FilterIcon, SortIcon } from '@/components/icons/Icons';
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
      className={`ml-1 flex size-7 shrink-0 items-center justify-center rounded-full border border-brand-outline/30 bg-true-surface-variant text-brand-muted shadow-sm hover:text-brand-primary ${CHROME_FOCUS}`}
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
  onSortOrderClick: () => void;
  /** When true, search overrides sort order (D14 relevance) — sort chip is disabled. */
  sortDisabled?: boolean;
  /** Colours narrowing the list; several can be on at once (NL-04). */
  selectedColors: readonly number[];
  /** A swatch was pressed: toggle that colour. */
  onColorToggle: (argb: number) => void;
  onColorsClear: () => void;
  labels: Label[];
  selectedLabelNames: readonly string[];
  onLabelToggle: (name: string) => void;
  onLabelsClear: () => void;
  hasActiveFilters: boolean;
  onClearFilters: () => void;
  /** Opens the Filters sheet. Without it the row has no Filters chip. */
  onOpenFilters?: () => void;
  /** How many sheet filters are on, shown on the chip. */
  filterCount?: number;
}

export function FilterRow({
  sortOrder,
  onSortOrderClick,
  sortDisabled = false,
  selectedColors,
  onColorToggle,
  onColorsClear,
  labels,
  selectedLabelNames,
  onLabelToggle,
  onLabelsClear,
  hasActiveFilters,
  onClearFilters,
  onOpenFilters,
  filterCount = 0,
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
      <div className="relative flex items-center">
        <div
          ref={colorRow.ref}
          tabIndex={0}
          role="group"
          aria-label="Filters"
          className={`flex min-w-0 flex-1 items-center gap-2.5 overflow-x-auto px-shell py-1.5 scrollbar-none ${CHROME_FOCUS}`}
        >
          <FilterChip
            compact
            label={sortLabel}
            selected={true}
            onClick={onSortOrderClick}
            disabled={sortDisabled}
            ariaLabel={
              sortDisabled
                ? 'Sort locked to relevance while searching'
                : `Sort: ${SORT_LABELS[sortOrder]}. Choose sort order`
            }
            title={sortDisabled ? 'Relevance while searching' : 'Choose sort order'}
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

          {onOpenFilters ? (
            <FilterChip
              compact
              label={filterCount > 0 ? `Filters · ${filterCount}` : 'Filters'}
              selected={filterCount > 0}
              onClick={onOpenFilters}
              ariaLabel={filterCount > 0 ? `Filters, ${filterCount} on. Open filters` : 'Open filters'}
              leading={<FilterIcon size={14} />}
            />
          ) : null}

          {hasActiveFilters ? (
            <FilterChip compact label="Clear" selected onClick={onClearFilters} />
          ) : null}

          <div
            className="flex min-h-9 min-w-0 items-center"
            role="group"
            aria-label="Color filter"
          >
            <ColorSwatchRow
              selectedColor={null}
              selectedColors={selectedColors}
              onSelect={(argb) => {
                if (argb != null) onColorToggle(argb);
              }}
              allSelected={selectedColors.length === 0}
              onSelectAll={onColorsClear}
            />
          </div>
        </div>
        <ScrollCue target={colorRow.ref} visible={colorRow.canScrollRight} />
      </div>

      {labels.length > 0 ? (
        <div className="relative flex items-center">
          <div
            ref={labelRow.ref}
            tabIndex={0}
            role="group"
            aria-label="Label filter"
            className={`flex min-w-0 flex-1 gap-1.5 overflow-x-auto px-shell py-0.5 scrollbar-none ${CHROME_FOCUS}`}
          >
            <FilterChip
              compact
              label="All labels"
              selected={selectedLabelNames.length === 0}
              pressed={selectedLabelNames.length === 0}
              onClick={onLabelsClear}
            />
            {labels.map((label) => {
              const on = selectedLabelNames.includes(label.name);
              return (
                <FilterChip
                  key={label.id}
                  compact
                  label={label.name}
                  selected={on}
                  pressed={on}
                  onClick={() => onLabelToggle(label.name)}
                />
              );
            })}
          </div>
          <ScrollCue target={labelRow.ref} visible={labelRow.canScrollRight} />
        </div>
      ) : null}
    </div>
  );
}
