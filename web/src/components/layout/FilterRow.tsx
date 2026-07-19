import { useEffect, useRef, useState } from 'react';
import { FilterChip } from '@/components/layout/FilterChip';
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
  sortOrder: SortOrder;
  onSortOrderChange: (order: SortOrder) => void;
  selectedColor: number | null;
  onColorSelect: (color: number | null) => void;
  labels: Label[];
  selectedLabelName: string | null;
  onLabelSelect: (name: string | null) => void;
  hasActiveFilters: boolean;
  onClearFilters: () => void;
}


function SortFilterChip({
  sortOrder,
  onSortOrderChange,
}: {
  sortOrder: SortOrder;
  onSortOrderChange: (order: SortOrder) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  return (
    <div ref={rootRef} className="relative shrink-0">
      <FilterChip
        label={SORT_LABELS[sortOrder]}
        compact
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-haspopup="listbox"
      />
      {open ? (
        <div
          role="listbox"
          aria-label="Sort order"
          className="absolute left-0 top-full z-40 mt-1 min-w-[11rem] overflow-hidden rounded-note border border-brand-outline/50 bg-true-surface py-1 shadow-lg"
        >
          {SORT_ORDER.map((order) => (
            <button
              key={order}
              type="button"
              role="option"
              aria-selected={order === sortOrder}
              onClick={() => {
                onSortOrderChange(order);
                setOpen(false);
              }}
              className={`flex w-full px-3 py-2.5 text-left text-sm transition-colors ${
                order === sortOrder
                  ? 'bg-true-surface-variant/70 font-semibold text-brand-primary'
                  : 'text-brand-primary interactive-hover'
              }`}
            >
              {SORT_LABELS[order] === 'Manual' ? 'Manual sort' : order === 'newest' ? 'Newest first' : 'Oldest first'}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function FilterRow({
  sortOrder,
  onSortOrderChange,
  selectedColor,
  onColorSelect,
  labels,
  selectedLabelName,
  onLabelSelect,
  hasActiveFilters,
  onClearFilters,
}: FilterRowProps) {
  const showLabelRow = labels.length > 0;

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
            compact
            selected={selectedLabelName === null}
            onClick={() => onLabelSelect(null)}
          />
          {labels.map((label) => (
            <FilterChip
              key={label.id}
              label={label.name}
              compact
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
