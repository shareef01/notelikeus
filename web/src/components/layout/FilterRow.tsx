import { ColorSwatchRow } from '@/components/layout/ColorSwatch';
import type { Label } from '@/types/label';

interface FilterChipProps {
  label: string;
  selected?: boolean;
  onClick?: () => void;
  disabled?: boolean;
}

function FilterChip({ label, selected = false, onClick, disabled = false }: FilterChipProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`filter-chip shrink-0 ${selected ? 'filter-chip-active' : 'filter-chip-inactive'} ${
        disabled ? 'cursor-default opacity-70' : 'cursor-pointer'
      }`}
    >
      {label}
    </button>
  );
}

const SORT_LABELS = {
  manual: 'Manual order',
  newest: 'Newest first',
  oldest: 'Oldest first',
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
    <div className="flex flex-col gap-0.5 pb-1">
      <div className="flex gap-1.5 overflow-x-auto px-layout-gap py-1 scrollbar-none md:flex-wrap md:overflow-visible">
        <FilterChip label={SORT_LABELS[sortOrder]} selected onClick={onSortOrderCycle} />
        {hasActiveFilters ? (
          <FilterChip label="Filters active" selected onClick={onClearFilters} />
        ) : null}
        <FilterChip
          label="All colors"
          selected={selectedColor === null}
          onClick={() => onColorSelect(null)}
        />
        <ColorSwatchRow selectedColor={selectedColor} onSelect={onColorSelect} />
      </div>

      {labels.length > 0 ? (
        <div className="flex gap-1.5 overflow-x-auto px-layout-gap py-1 scrollbar-none md:flex-wrap md:overflow-visible">
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
