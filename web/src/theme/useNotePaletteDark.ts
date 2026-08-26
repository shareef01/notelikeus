import { useSettingsStore, type ThemeBase } from '@/store/settingsStore';
import { useEffect, useState } from 'react';

function isDarkForBase(base: ThemeBase): boolean {
  if (base === 'light') return false;
  if (base === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  return true;
}

/**
 * Whether the note colour palette should use dark (rich) or light (pastel) swatches.
 *
 * Keys off the *base* only. The accent tints the app's chrome, not the note swatches, and the
 * black level does not change which half of the palette a note is drawn from -- both would be the
 * wrong question to ask here.
 */
export function useNotePaletteDark(): boolean {
  const base = useSettingsStore((s) => s.theme.base);
  const [isDark, setIsDark] = useState(() => isDarkForBase(base));

  useEffect(() => {
    const sync = () => setIsDark(isDarkForBase(base));
    sync();
    if (base !== 'system') return;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    media.addEventListener('change', sync);
    return () => media.removeEventListener('change', sync);
  }, [base]);

  return isDark;
}
