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
}

function FilterChip({
  label,
  selected = false,
  onClick,
  disabled = false,
  leading,
}: FilterChipProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`filter-chip shrink-0 gap-1.5 ${selected ? 'filter-chip-active' : 'filter-chip-inactive'} ${
        disabled ? 'cursor-default opacity-70' : 'cursor-pointer'
      }`}
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
    <div className="flex flex-col gap-1 pb-1.5">
      <div className="flex items-center gap-2 overflow-x-auto px-3 py-1 scrollbar-none sm:px-4 lg:px-6">
        <FilterChip
          label={SORT_LABELS[sortOrder]}
          onClick={onSortOrderCycle}
          leading={<SortIcon size={14} className="opacity-70" />}
        />

        {hasActiveFilters ? (
          <FilterChip label="Clear filters" selected onClick={onClearFilters} />
        ) : null}

        <div
          className="flex min-w-0 items-center gap-1.5 rounded-full border border-brand-outline/40 bg-true-surface-variant/30 px-2 py-1"
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
        <div className="flex gap-1.5 overflow-x-auto px-3 py-1 scrollbar-none sm:px-4 md:flex-wrap md:overflow-visible lg:px-6">
          <FilterChip
            label="All labels"
            selected={selectedLabelName === null}
            onClick={() => onLabelSelect(null)}
          />
          {labels.map((label) => (
            <FilterChip
              key={label.id}
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
