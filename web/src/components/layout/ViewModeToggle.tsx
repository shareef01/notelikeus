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
        className="flex size-11 shrink-0 items-center justify-center rounded-full border border-brand-outline/40 bg-true-surface-variant/40 text-brand-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary md:hidden"
        aria-label={`View: ${current.label}. Tap to change`}
        title={current.label}
      >
        <CurrentIcon size={18} />
      </button>

      <div
        className="hidden h-11 shrink-0 items-center gap-0.5 rounded-full border border-brand-outline/40 bg-true-surface-variant/40 p-1 md:flex"
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
              className={`flex size-9 items-center justify-center rounded-full transition-[background-color,color,opacity] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary ${
                selected
                  ? 'bg-white/12 text-brand-primary opacity-100'
                  : 'text-brand-muted opacity-70 hover:bg-white/5 hover:opacity-90'
              }`}
            >
              <Icon size={16} />
            </button>
          );
        })}
      </div>
    </>
  );
}
