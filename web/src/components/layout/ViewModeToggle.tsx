import { GridViewIcon, ViewDenseIcon, ViewListIcon } from '@/components/icons/Icons';
import type { ViewColumns } from '@/store/uiStore';

const MODES: {
  value: ViewColumns;
  label: string;
  icon: typeof ViewListIcon;
}[] = [
  { value: 1, label: 'List', icon: ViewListIcon },
  { value: 2, label: 'Grid', icon: GridViewIcon },
  { value: 3, label: 'Dense', icon: ViewDenseIcon },
];

interface ViewModeToggleProps {
  value: ViewColumns;
  onChange: (value: ViewColumns) => void;
}

/** Segmented list / grid / dense control for the notes board. */
export function ViewModeToggle({ value, onChange }: ViewModeToggleProps) {
  return (
    <div
      className="hidden h-9 shrink-0 items-center gap-0.5 rounded-full border border-brand-outline/40 bg-true-surface-variant/40 p-1 sm:flex"
      role="radiogroup"
      aria-label="Notes view"
    >
      {MODES.map(({ value: mode, label, icon: Icon }) => {
        const selected = value === mode;
        return (
          <button
            key={mode}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={label}
            title={label}
            onClick={() => onChange(mode)}
            className={`flex size-7 items-center justify-center rounded-full transition-[background-color,color,opacity] ${
              selected
                ? 'bg-white/12 text-brand-primary opacity-100'
                : 'text-brand-muted opacity-55 hover:bg-white/5 hover:opacity-90'
            }`}
          >
            <Icon size={16} />
          </button>
        );
      })}
    </div>
  );
}
