import { useSettingsStore } from '@/store/settingsStore';
import type { AccentColor, ThemeBase, ThemePreference } from '@/store/settingsStore';
import { useEffect } from 'react';

const BASE_CLASSES = ['theme-light', 'theme-dark'] as const;
const ACCENT_CLASSES = ['accent-blue', 'accent-green'] as const;
const AMOLED_CLASS = 'amoled';

/** Which palette is on screen, once the OS has had its say. */
function resolveBase(base: ThemeBase, prefersDark: boolean): 'light' | 'dark' {
  if (base === 'system') return prefersDark ? 'dark' : 'light';
  return base;
}

function accentClassName(accent: AccentColor): string | null {
  return accent === 'neutral' ? null : `accent-${accent}`;
}

/**
 * The classes a preference resolves to, given what the OS is asking for.
 *
 * Pure, and exported, because this is the whole of the decision -- everything else in this file is
 * plumbing that puts the result on an element and listens for the OS changing its mind.
 */
export function themeClassNames(theme: ThemePreference, prefersDark: boolean): string[] {
  const effective = resolveBase(theme.base, prefersDark);
  const isDark = effective === 'dark';
  const classes = [`theme-${effective}`];

  const accent = accentClassName(theme.accent);
  if (accent) classes.push(accent);

  // Black backgrounds are a dark-theme idea. Following the system to light used to land on the
  // OLED palette outright, which is how "System" could turn the app pure black unasked.
  if (theme.amoled && isDark) classes.push(AMOLED_CLASS);

  if (isDark) classes.push('dark');
  return classes;
}

/**
 * Applies the persisted appearance settings to the document root.
 *
 * Three independent classes rather than one fused theme name: a base, an optional accent, and an
 * optional black level. The CSS composes them, which is what lets a blue theme also be OLED —
 * something the six named themes could not express at all.
 */
export function ThemeApplier() {
  const theme = useSettingsStore((s) => s.theme);

  useEffect(() => {
    const root = document.documentElement;

    const apply = () => {
      root.classList.remove(...BASE_CLASSES, ...ACCENT_CLASSES, AMOLED_CLASS, 'dark');
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      root.classList.add(...themeClassNames(theme, prefersDark));
    };

    apply();

    if (theme.base !== 'system') return;

    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => apply();
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, [theme]);

  return null;
}
