import { ColorSwatchRow } from '@/components/layout/ColorSwatch';
import { SortIcon } from '@/components/icons/Icons';
import type { Label } from '@/types/label';
import type { ReactNode } from 'react';

interface FilterChipProps {
  label: string;
  selected?: boolean;
  onClick?: () => void;
  disabled?: boolean;
  leading?: ReactNode;
  compact?: boolean;
}

function FilterChip({
  label,
  selected = false,
  onClick,
  disabled = false,
  leading,
  compact = false,
}: FilterChipProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`filter-chip shrink-0 gap-1.5 ${compact ? 'min-h-9 px-3 text-xs sm:px-3.5' : ''} ${
        selected ? 'filter-chip-active' : 'filter-chip-inactive'
      } ${disabled ? 'cursor-default opacity-70' : 'cursor-pointer'}`}
    >
      {leading}
      {label}
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
  selectedColor,
  onColorSelect,
  labels,
  selectedLabelName,
  onLabelSelect,
  hasActiveFilters,
  onClearFilters,
}: FilterRowProps) {
  return (
    <div className="flex flex-col gap-1.5 pb-2">
      <div className="flex items-center gap-2.5 overflow-x-auto px-3 py-1.5 scrollbar-none sm:px-4 lg:px-6">
        <FilterChip
          compact
          label={SORT_LABELS[sortOrder]}
          onClick={onSortOrderCycle}
          leading={<SortIcon size={14} className="opacity-80" />}
        />

        {hasActiveFilters ? (
          <FilterChip compact label="Clear" selected onClick={onClearFilters} />
        ) : null}

        <div
          className="flex h-9 min-w-0 items-center rounded-full border border-brand-outline/35 bg-true-surface-variant/25 px-1.5 shadow-[inset_0_1px_0_rgb(var(--outline-rgb)/0.3)]"
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
            onClick={() => onLabelSelect(null)}
          />
          {labels.map((label) => (
            <FilterChip
              key={label.id}
              compact
              label={label.name}
              selected={selectedLabelName === label.name}
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
