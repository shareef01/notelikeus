import { ColorSwatchRow } from '@/components/layout/ColorSwatch';
import { ChevronRightIcon, SortIcon } from '@/components/icons/Icons';
import type { Label } from '@/types/label';
import type { ReactNode } from 'react';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';

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

  return (
    <div className="flex flex-col gap-1.5 pb-2">
      <div className="flex items-center gap-2.5 overflow-x-auto px-3 py-1.5 scrollbar-none sm:px-4 lg:px-6">
        <FilterChip
          compact
          label={sortLabel}
          selected={!sortDisabled}
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

      {labels.length > 0 ? (
        <div className="flex gap-1.5 overflow-x-auto px-3 py-0.5 scrollbar-none sm:px-4 md:flex-wrap md:overflow-visible lg:px-6">
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
      ) : null}
    </div>
  );
}
