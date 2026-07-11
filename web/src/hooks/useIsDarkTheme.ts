import { useSettingsStore, type AppTheme } from '@/store/settingsStore';
import { useSyncExternalStore } from 'react';

function resolveIsDark(theme: AppTheme): boolean {
  if (theme === 'light') return false;
  if (theme === 'auto') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  return true;
}

function subscribeToColorScheme(onChange: () => void) {
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  media.addEventListener('change', onChange);
  return () => media.removeEventListener('change', onChange);
}

/** Whether the active app theme resolves to a dark color palette. */
export function useIsDarkTheme(): boolean {
  const appTheme = useSettingsStore((s) => s.appTheme);
  const systemDark = useSyncExternalStore(
    subscribeToColorScheme,
    () => window.matchMedia('(prefers-color-scheme: dark)').matches,
    () => true,
  );
  if (appTheme === 'auto') return systemDark;
  return resolveIsDark(appTheme);
}
