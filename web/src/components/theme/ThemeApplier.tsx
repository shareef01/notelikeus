import { useSettingsStore } from '@/store/settingsStore';
import { useEffect } from 'react';

const THEME_CLASSES = [
  'theme-light',
  'theme-dark',
  'theme-true-dark',
  'theme-midnight',
  'theme-forest',
] as const;

function themeClassName(theme: string): string {
  return `theme-${theme.replaceAll('_', '-')}`;
}

/** Applies persisted appearance settings to the document root. */
export function ThemeApplier() {
  const appTheme = useSettingsStore((s) => s.appTheme);

  useEffect(() => {
    const root = document.documentElement;

    const apply = () => {
      root.classList.remove(...THEME_CLASSES);

      let effective = appTheme;
      let isDark = appTheme !== 'light';

      if (appTheme === 'auto') {
        isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        effective = isDark ? 'true_dark' : 'light';
      }

      root.classList.add(themeClassName(effective));
      root.classList.toggle('dark', isDark);
    };

    apply();

    if (appTheme !== 'auto') return;

    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => apply();
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, [appTheme]);

  return null;
}
