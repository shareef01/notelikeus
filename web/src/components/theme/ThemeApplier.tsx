import { useSettingsStore } from '@/store/settingsStore';
import { useEffect } from 'react';

const THEME_CLASSES = [
  'theme-light',
  'theme-dark',
  'theme-true-dark',
  'theme-midnight',
  'theme-forest',
] as const;

function applyTheme(appTheme: ReturnType<typeof useSettingsStore.getState>['appTheme']) {
  const root = document.documentElement;
  root.classList.remove(...THEME_CLASSES);

  if (appTheme === 'auto') {
    const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    root.classList.add(isDark ? 'theme-true-dark' : 'theme-light');
    root.classList.toggle('dark', isDark);
    return;
  }

  root.classList.add(`theme-${appTheme.replace('_', '-')}`);
  root.classList.toggle('dark', appTheme !== 'light');
}

/** Applies persisted appearance settings to the document root. */
export function ThemeApplier() {
  const appTheme = useSettingsStore((s) => s.appTheme);

  useEffect(() => {
    applyTheme(appTheme);

    if (appTheme !== 'auto') return;

    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => applyTheme('auto');
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, [appTheme]);

  return null;
}
