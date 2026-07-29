import { useSettingsStore, type AppTheme } from '@/store/settingsStore';
import { useEffect, useState } from 'react';

function isDarkForTheme(appTheme: AppTheme): boolean {
  if (appTheme === 'light') return false;
  if (appTheme === 'auto') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  return true;
}

/** Whether the note color palette should use dark (rich) or light (pastel) swatches. */
export function useNotePaletteDark(): boolean {
  const appTheme = useSettingsStore((s) => s.appTheme);
  const [isDark, setIsDark] = useState(() => isDarkForTheme(appTheme));

  useEffect(() => {
    const sync = () => setIsDark(isDarkForTheme(appTheme));
    sync();
    if (appTheme !== 'auto') return;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    media.addEventListener('change', sync);
    return () => media.removeEventListener('change', sync);
  }, [appTheme]);

  return isDark;
}
