import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/**
 * The six themes this client used to offer, kept only so stored values still resolve.
 *
 * Nothing writes these any more. They are read once, migrated, and never seen again — see
 * {@link toThemePreference}, which mirrors `AppTheme.toThemePreference` in the Kotlin clients.
 */
export type LegacyAppTheme = 'auto' | 'light' | 'dark' | 'true_dark' | 'midnight' | 'forest';

/** Light, dark, or whatever the OS says. */
export type ThemeBase = 'system' | 'light' | 'dark';

/**
 * The hue laid over the base theme.
 *
 * `blue` and `green` are what Midnight and Forest actually were — a dark scheme with a tinted
 * primary and tinted surfaces — now expressible independently of the black level, which is the
 * whole point of the split: Midnight could never be OLED before, because there was one black level
 * per named theme.
 */
export type AccentColor = 'neutral' | 'blue' | 'green';

export interface ThemePreference {
  base: ThemeBase;
  accent: AccentColor;
  /** True black backgrounds. Only meaningful on a dark base. */
  amoled: boolean;
}

export const DEFAULT_THEME: ThemePreference = {
  base: 'dark',
  accent: 'neutral',
  amoled: true,
};

/**
 * Resolves one of the six stored theme names into the three independent settings.
 *
 * A direct mirror of `AppTheme.toThemePreference` in `ThemePreference.kt`, including which of the
 * six carry their own accent and black level rather than deferring to the stored ones. The Kotlin
 * version and its tests are the specification; this exists so a user with both clients sees the
 * same settings screen and the same result.
 */
export function toThemePreference(
  stored: LegacyAppTheme,
  storedAmoled = false,
  storedAccent: AccentColor = 'neutral',
): ThemePreference {
  switch (stored) {
    case 'auto':
      return { base: 'system', amoled: storedAmoled, accent: storedAccent };
    case 'light':
      return { base: 'light', amoled: storedAmoled, accent: storedAccent };
    case 'dark':
      return { base: 'dark', amoled: storedAmoled, accent: storedAccent };
    // The three below *were* a black level or a hue, so they carry their own and ignore the
    // stored ones. Midnight was never "dark plus blue you chose"; it was blue.
    case 'true_dark':
      return { base: 'dark', amoled: true, accent: 'neutral' };
    case 'midnight':
      return { base: 'dark', amoled: false, accent: 'blue' };
    case 'forest':
      return { base: 'dark', amoled: false, accent: 'green' };
  }
}

interface SettingsState {
  theme: ThemePreference;
  setThemeBase: (base: ThemeBase) => void;
  setAccentColor: (accent: AccentColor) => void;
  setAmoled: (amoled: boolean) => void;
}

/** Persisted shape before the split, as it still sits in localStorage for existing users. */
interface LegacyPersistedState {
  appTheme?: LegacyAppTheme;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      theme: DEFAULT_THEME,
      setThemeBase: (base) =>
        set((state) => (state.theme.base === base ? state : { theme: { ...state.theme, base } })),
      setAccentColor: (accent) =>
        set((state) =>
          state.theme.accent === accent ? state : { theme: { ...state.theme, accent } },
        ),
      setAmoled: (amoled) =>
        set((state) =>
          state.theme.amoled === amoled ? state : { theme: { ...state.theme, amoled } },
        ),
    }),
    {
      name: 'notelikeus-settings',
      skipHydration: true,
      version: 1,
      // Read-time only. Nothing rewrites the old key on load, so a user who downgrades still has
      // their theme — the same call the Kotlin clients made, for the same reason.
      migrate: (persisted, version) => {
        if (version >= 1) return persisted as SettingsState;
        const legacy = (persisted ?? {}) as LegacyPersistedState;
        return {
          theme: legacy.appTheme ? toThemePreference(legacy.appTheme) : DEFAULT_THEME,
        } as SettingsState;
      },
    },
  ),
);
