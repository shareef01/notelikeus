import { useSettingsStore } from '@/store/settingsStore';
import { useEffect } from 'react';

/** Applies persisted appearance settings to the document root. */
export function ThemeApplier() {
  const useMonochromeTheme = useSettingsStore((s) => s.useMonochromeTheme);
  const trueDarkMode = useSettingsStore((s) => s.trueDarkMode);

  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('monochrome', useMonochromeTheme);
    root.classList.toggle('true-dark', trueDarkMode);
    root.classList.toggle('soft-dark', !trueDarkMode);
  }, [useMonochromeTheme, trueDarkMode]);

  return null;
}
