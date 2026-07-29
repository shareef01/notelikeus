import { CheckIcon } from '@/components/icons/Icons';
import type { AppTheme } from '@/store/settingsStore';

export const THEME_ORDER: AppTheme[] = [
  'auto',
  'light',
  'dark',
  'true_dark',
  'midnight',
  'forest',
];

type ThemeMeta = {
  label: string;
  /** Main fill — surface color from the theme */
  surface: string;
  /** Accent chip — primary / highlight hue */
  accent: string;
  /** Optional second half for System split */
  surfaceAlt?: string;
  /** Check badge uses dark ink on light surfaces */
  lightCheck?: boolean;
};

const THEME_META: Record<AppTheme, ThemeMeta> = {
  auto: {
    label: 'System',
    surface: '#f7f7f7',
    surfaceAlt: '#1c1c1c',
    accent: '#8b8b8b',
    lightCheck: true,
  },
  light: {
    label: 'Light',
    surface: '#ffffff',
    accent: '#111111',
    lightCheck: true,
  },
  dark: {
    label: 'Dark',
    surface: '#1c1c1c',
    accent: '#f5f5f5',
  },
  true_dark: {
    label: 'OLED',
    surface: '#000000',
    accent: '#ffffff',
  },
  midnight: {
    label: 'Midnight',
    surface: '#0c111c',
    accent: '#8eb6ff',
  },
  forest: {
    label: 'Forest',
    surface: '#0f1610',
    accent: '#8fd49a',
  },
};

interface ThemePickerProps {
  value: AppTheme;
  onChange: (theme: AppTheme) => void;
}

function ThemeSwatch({
  meta,
  selected,
}: {
  meta: ThemeMeta;
  selected: boolean;
}) {
  return (
    <span
      className={`relative flex size-10 items-center justify-center overflow-hidden rounded-full sm:size-11 ${
        selected
          ? 'ring-2 ring-brand-primary ring-offset-2 ring-offset-true-surface'
          : 'ring-1 ring-brand-outline/45'
      }`}
    >
      {meta.surfaceAlt ? (
        <>
          <span
            className="absolute inset-0"
            style={{
              background: `linear-gradient(120deg, ${meta.surface} 49.5%, ${meta.surfaceAlt} 50.5%)`,
            }}
          />
          <span
            className="absolute left-[28%] top-[30%] size-1.5 rounded-full shadow-sm"
            style={{ backgroundColor: '#111111' }}
            aria-hidden
          />
          <span
            className="absolute right-[28%] bottom-[30%] size-1.5 rounded-full shadow-sm"
            style={{ backgroundColor: '#f5f5f5' }}
            aria-hidden
          />
        </>
      ) : (
        <>
          <span className="absolute inset-0" style={{ backgroundColor: meta.surface }} />
          <span
            className="absolute bottom-[22%] left-1/2 size-2 -translate-x-1/2 rounded-full shadow-sm"
            style={{ backgroundColor: meta.accent }}
            aria-hidden
          />
        </>
      )}

      {selected ? (
        <span
          className={`relative z-10 flex size-5 items-center justify-center rounded-full shadow-md ${
            meta.lightCheck ? 'bg-neutral-900 text-white' : 'bg-white text-neutral-900'
          }`}
          aria-hidden
        >
          <CheckIcon size={11} />
        </span>
      ) : null}
    </span>
  );
}

export function ThemePicker({ value, onChange }: ThemePickerProps) {
  return (
    <div
      className="grid grid-cols-3 gap-x-3 gap-y-4 px-4 py-4 sm:gap-x-4 sm:px-5 sm:py-5"
      role="radiogroup"
      aria-label="App theme"
    >
      {THEME_ORDER.map((theme) => {
        const meta = THEME_META[theme];
        const selected = value === theme;
        return (
          <button
            key={theme}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={meta.label}
            onClick={() => onChange(theme)}
            className={`flex flex-col items-center gap-2 rounded-xl px-1 py-1.5 outline-none transition-colors focus-visible:ring-2 focus-visible:ring-brand-primary/50 ${
              selected ? 'bg-brand-primary/[0.06]' : 'hover:bg-white/[0.03]'
            }`}
          >
            <ThemeSwatch meta={meta} selected={selected} />
            <span
              className={`text-center text-[11px] leading-none tracking-tight sm:text-xs ${
                selected ? 'font-semibold text-brand-primary' : 'font-medium text-brand-muted'
              }`}
            >
              {meta.label}
            </span>
          </button>
        );
      })}
    </div>
  );
}
