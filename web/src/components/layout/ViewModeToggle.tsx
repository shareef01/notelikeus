import { GridViewIcon, ViewDenseIcon, ViewListIcon } from '@/components/icons/Icons';
import type { ViewColumns } from '@/store/uiStore';

const MODES: {
  value: ViewColumns;
  label: string;
  shortLabel: string;
  icon: typeof ViewListIcon;
}[] = [
  { value: 1, label: 'List — one column', shortLabel: 'List', icon: ViewListIcon },
  { value: 2, label: 'Grid — comfortable cards', shortLabel: 'Grid', icon: GridViewIcon },
  { value: 3, label: 'Compact — more columns', shortLabel: 'Compact', icon: ViewDenseIcon },
];

interface ViewModeToggleProps {
  value: ViewColumns;
  onChange: (value: ViewColumns) => void;
}

function nextMode(value: ViewColumns): ViewColumns {
  return value === 3 ? 1 : ((value + 1) as ViewColumns);
}

/** Segmented control from md up; single cycle button on phones to keep search space. */
export function ViewModeToggle({ value, onChange }: ViewModeToggleProps) {
  const current = MODES.find((mode) => mode.value === value) ?? MODES[1];
  const CurrentIcon = current.icon;

  return (
    <>
      <button
        type="button"
        onClick={() => onChange(nextMode(value))}
        className="flex size-9 shrink-0 items-center justify-center rounded-full border border-brand-outline/40 bg-true-surface-variant/70 text-brand-secondary shadow-sm transition-colors hover:text-brand-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary md:hidden"
        aria-label={`View: ${current.shortLabel}. Tap to change`}
        title={current.label}
      >
        <CurrentIcon size={18} />
      </button>

      <div
        className="hidden h-9 shrink-0 items-center gap-0.5 rounded-full border border-brand-outline/40 bg-true-surface-variant/70 p-0.5 shadow-sm md:flex"
        role="radiogroup"
        aria-label="Notes view size"
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
              className={`flex size-8 items-center justify-center rounded-full transition-all duration-150 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary ${
                selected
                  ? 'bg-brand-primary text-true-surface shadow-sm'
                  : 'text-brand-secondary hover:bg-brand-primary/10 hover:text-brand-primary'
              }`}
            >
              <Icon size={18} />
            </button>
          );
        })}
      </div>
    </>
  );
}
