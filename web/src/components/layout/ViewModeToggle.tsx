import { GridViewIcon, ViewDenseIcon, ViewListIcon } from '@/components/icons/Icons';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
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
        className={`flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-brand-primary/5 hover:text-brand-primary md:hidden ${CHROME_FOCUS}`}
        aria-label={`View: ${current.shortLabel}. Tap to change`}
        title={current.label}
      >
        <CurrentIcon size={20} />
      </button>

      <div
        className="hidden shrink-0 items-center md:flex"
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
              className={`flex size-9 items-center justify-center rounded-full transition-colors ${CHROME_FOCUS} ${
                selected
                  ? 'text-brand-primary'
                  : 'text-brand-muted hover:bg-brand-primary/5 hover:text-brand-primary'
              }`}
            >
              <Icon size={20} />
            </button>
          );
        })}
      </div>
    </>
  );
}
