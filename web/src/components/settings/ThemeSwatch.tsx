import type { AppTheme } from '@/store/settingsStore';

const THEME_SWATCHES: Record<AppTheme, { background: string; border?: string }> = {
  auto: {
    background: 'linear-gradient(135deg, #F7F7F7 0%, #F7F7F7 50%, #000000 50%, #000000 100%)',
  },
  light: { background: '#F7F7F7', border: '#D8D8D8' },
  dark: { background: '#121212', border: '#333333' },
  true_dark: { background: '#000000', border: '#222222' },
  midnight: { background: '#080C14', border: '#232D3B' },
  forest: { background: '#0A0F0A', border: '#263326' },
};

export const THEME_ORDER: AppTheme[] = ['auto', 'light', 'dark', 'true_dark', 'midnight', 'forest'];

export const THEME_LABELS: Record<AppTheme, string> = {
  auto: 'Auto',
  light: 'Light',
  dark: 'Dark',
  true_dark: 'True Dark',
  midnight: 'Midnight',
  forest: 'Forest',
};

export function ThemeSwatch({ theme }: { theme: AppTheme }) {
  const swatch = THEME_SWATCHES[theme];
  return (
    <span
      className="size-7 shrink-0 rounded-full border border-brand-outline/40 shadow-sm"
      style={{ background: swatch.background, borderColor: swatch.border }}
      aria-hidden
    />
  );
}

export function ThemeSwatchStrip({ activeTheme }: { activeTheme: AppTheme }) {
  return (
    <div className="flex items-center gap-1.5" aria-hidden>
      {THEME_ORDER.map((theme) => {
        const swatch = THEME_SWATCHES[theme];
        const active = theme === activeTheme;
        return (
          <span
            key={theme}
            className={`size-4 rounded-full border transition-transform ${
              active ? 'scale-110 border-brand-primary ring-1 ring-brand-primary/40' : 'border-brand-outline/30 opacity-70'
            }`}
            style={{ background: swatch.background, borderColor: active ? undefined : swatch.border }}
          />
        );
      })}
    </div>
  );
}

interface ThemePickerGridProps {
  activeTheme: AppTheme;
  onSelect: (theme: AppTheme) => void;
}

export function ThemePickerGrid({ activeTheme, onSelect }: ThemePickerGridProps) {
  return (
    <div className="grid grid-cols-2 gap-2 px-4 pb-3" role="listbox" aria-label="Choose app theme">
      {THEME_ORDER.map((theme) => {
        const selected = theme === activeTheme;
        return (
          <button
            key={theme}
            type="button"
            role="option"
            aria-selected={selected}
            onClick={() => onSelect(theme)}
            className={`flex min-h-11 items-center gap-3 rounded-note border px-3 py-2 text-left transition-colors ${
              selected
                ? 'border-brand-primary bg-true-surface-variant/55'
                : 'border-brand-outline/50 interactive-hover'
            }`}
          >
            <ThemeSwatch theme={theme} />
            <span className="text-sm font-medium text-brand-primary">{THEME_LABELS[theme]}</span>
          </button>
        );
      })}
    </div>
  );
}
