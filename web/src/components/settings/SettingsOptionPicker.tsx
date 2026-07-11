interface SettingsOptionPickerProps<T extends string | number> {
  options: readonly T[];
  labels: Record<T, string>;
  active: T;
  onSelect: (value: T) => void;
  ariaLabel: string;
}

export function SettingsOptionPicker<T extends string | number>({
  options,
  labels,
  active,
  onSelect,
  ariaLabel,
}: SettingsOptionPickerProps<T>) {
  return (
    <div className="grid grid-cols-1 gap-2 px-4 pb-3 sm:grid-cols-2" role="listbox" aria-label={ariaLabel}>
      {options.map((option) => {
        const selected = option === active;
        return (
          <button
            key={String(option)}
            type="button"
            role="option"
            aria-selected={selected}
            onClick={() => onSelect(option)}
            className={`flex min-h-11 items-center rounded-note border px-3 py-2.5 text-left text-sm font-medium transition-colors ${
              selected
                ? 'border-brand-primary bg-true-surface-variant/55 text-brand-primary'
                : 'border-brand-outline/50 text-brand-primary interactive-hover'
            }`}
          >
            {labels[option]}
          </button>
        );
      })}
    </div>
  );
}
