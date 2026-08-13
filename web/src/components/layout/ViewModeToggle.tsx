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
        className="flex h-9 shrink-0 items-center gap-1.5 rounded-full border border-brand-outline/30 bg-true-surface-variant/20 px-2.5 text-[11px] font-semibold tracking-wide text-brand-secondary shadow-sm transition-colors hover:bg-true-surface-variant/40 hover:text-brand-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary md:hidden"
        aria-label={`View: ${current.shortLabel}. Tap to change`}
        title={current.label}
      >
        <CurrentIcon size={16} />
        <span>{current.shortLabel}</span>
      </button>

      <div
        className="hidden h-9 shrink-0 items-center gap-0.5 rounded-full border border-brand-outline/30 bg-true-surface-variant/20 p-0.5 shadow-sm md:flex"
        role="radiogroup"
        aria-label="Notes view size"
      >
        {MODES.map(({ value: mode, label, shortLabel, icon: Icon }) => {
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
              className={`flex h-8 items-center gap-1.5 rounded-full px-2.5 text-[11px] font-semibold transition-all duration-150 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary ${
                selected
                  ? 'bg-brand-primary/20 text-brand-primary shadow-sm'
                  : 'text-brand-muted/60 hover:bg-brand-primary/8 hover:text-brand-secondary'
              }`}
            >
              <Icon size={15} />
              <span className="tracking-wide hidden sm:inline">
                {shortLabel}
              </span>
            </button>
          );
        })}
      </div>
    </>
  );
}
