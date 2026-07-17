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

const THEME_META: Record<
  AppTheme,
  { label: string; swatch: string; swatchAlt?: string }
> = {
  auto: { label: 'System', swatch: '#f4f4f4', swatchAlt: '#111111' },
  light: { label: 'Light', swatch: '#ffffff' },
  dark: { label: 'Dark', swatch: '#1e1e1e' },
  true_dark: { label: 'OLED', swatch: '#000000' },
  midnight: { label: 'Midnight', swatch: '#0d121d' },
  forest: { label: 'Forest', swatch: '#121812' },
};

interface ThemePickerProps {
  value: AppTheme;
  onChange: (theme: AppTheme) => void;
}

export function ThemePicker({ value, onChange }: ThemePickerProps) {
  return (
    <div
      className="flex flex-wrap justify-between gap-y-4 px-4 py-4 sm:justify-start sm:gap-x-5 sm:px-5 sm:py-5"
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
            className="flex w-[4.25rem] flex-col items-center gap-2 rounded-note outline-none focus-visible:ring-2 focus-visible:ring-brand-primary/50 sm:w-[4.75rem]"
          >
            <span
              className={`relative flex size-11 items-center justify-center rounded-full border-2 transition-[transform,box-shadow,border-color] sm:size-12 ${
                selected
                  ? 'scale-105 border-brand-primary shadow-[0_0_0_3px_rgb(var(--primary-rgb)/0.18)]'
                  : 'border-brand-outline/55 hover:border-brand-outline'
              }`}
              style={
                meta.swatchAlt
                  ? {
                      background: `linear-gradient(135deg, ${meta.swatch} 50%, ${meta.swatchAlt} 50%)`,
                    }
                  : { backgroundColor: meta.swatch }
              }
            >
              {selected ? (
                <span
                  className="flex size-[1.125rem] items-center justify-center rounded-full bg-white text-neutral-900 shadow-md ring-1 ring-black/15"
                  aria-hidden
                >
                  <CheckIcon size={11} />
                </span>
              ) : null}
            </span>
            <span
              className={`text-center text-[11px] leading-tight sm:text-xs ${
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
