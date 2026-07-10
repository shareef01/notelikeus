import { useSettingsStore } from '@/store/settingsStore';
import { useEffect } from 'react';

/** Applies persisted appearance settings to the document root. */
export function ThemeApplier() {
  const appTheme = useSettingsStore((s) => s.appTheme);

  useEffect(() => {
    const root = document.documentElement;

    // Clear existing theme classes
    root.classList.remove('theme-light', 'theme-dark', 'theme-true-dark', 'theme-midnight', 'theme-forest');

    if (appTheme === 'auto') {
        const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        root.classList.add(isDark ? 'theme-true-dark' : 'theme-light');
        root.classList.toggle('dark', isDark);
    } else {
        root.classList.add(`theme-${appTheme.replace('_', '-')}`);
        root.classList.toggle('dark', appTheme !== 'light');
    }
  }, [appTheme]);

  return null;
}
