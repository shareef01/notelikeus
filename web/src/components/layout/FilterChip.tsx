interface FilterChipProps {
  label: string;
  selected?: boolean;
  compact?: boolean;
  onClick?: () => void;
  disabled?: boolean;
  'aria-expanded'?: boolean;
  'aria-haspopup'?: boolean | 'listbox' | 'menu' | 'dialog' | 'grid' | 'tree';
}

export function FilterChip({
  label,
  selected = false,
  compact = false,
  onClick,
  disabled = false,
  'aria-expanded': ariaExpanded,
  'aria-haspopup': ariaHasPopup,
}: FilterChipProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-pressed={selected}
      aria-expanded={ariaExpanded}
      aria-haspopup={ariaHasPopup}
      className={`filter-chip ${compact ? 'filter-chip-compact' : ''} ${
        selected ? 'filter-chip-active' : 'filter-chip-inactive'
      } ${disabled ? 'cursor-default opacity-70' : 'cursor-pointer'}`}
    >
      {label}
    </button>
  );
}
