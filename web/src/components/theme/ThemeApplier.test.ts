import { describe, expect, it } from 'vitest';
import { themeClassNames } from './ThemeApplier';
import type { ThemePreference } from '@/store/settingsStore';

const preference = (over: Partial<ThemePreference> = {}): ThemePreference => ({
  base: 'dark',
  accent: 'neutral',
  amoled: false,
  ...over,
});

/**
 * Appearance is three independent classes on `<html>`, and the CSS composes them.
 *
 * The version this replaced fused them into one theme name, which is why a blue theme could never
 * also be pure black — there was one black level per named theme. It also resolved
 * System-with-a-dark-OS to the OLED palette outright, so choosing "System" could turn the app true
 * black without anyone asking for it.
 */
describe('themeClassNames', () => {
  it('applies a plain dark theme with no accent or black level', () => {
    expect(themeClassNames(preference(), false)).toEqual(['theme-dark', 'dark']);
  });

  it('applies a plain light theme, which is not "dark" to Tailwind', () => {
    expect(themeClassNames(preference({ base: 'light' }), false)).toEqual(['theme-light']);
  });

  /** The combination the six fused themes could not express at all. */
  it('composes an accent and a black level onto the same base', () => {
    expect(themeClassNames(preference({ accent: 'blue', amoled: true }), false)).toEqual([
      'theme-dark',
      'accent-blue',
      'amoled',
      'dark',
    ]);
  });

  it('follows the system to dark without reaching for pure black', () => {
    const classes = themeClassNames(preference({ base: 'system' }), true);

    expect(classes).toContain('theme-dark');
    // The bug this guards: System with a dark OS used to land on the OLED palette.
    expect(classes).not.toContain('amoled');
  });

  it('follows the system to light', () => {
    const classes = themeClassNames(preference({ base: 'system' }), false);

    expect(classes).toContain('theme-light');
    expect(classes).not.toContain('dark');
  });

  it('does not black out a light theme, even with pure black switched on', () => {
    const classes = themeClassNames(preference({ base: 'light', accent: 'green', amoled: true }), false);

    expect(classes).toEqual(['theme-light', 'accent-green']);
  });

  it('does not black out a system theme while the OS is light', () => {
    const classes = themeClassNames(preference({ base: 'system', amoled: true }), false);

    expect(classes).toContain('theme-light');
    expect(classes).not.toContain('amoled');
  });

  it('emits no accent class for neutral, so the base rule applies unmodified', () => {
    expect(themeClassNames(preference({ accent: 'neutral' }), false)).not.toContain('accent-neutral');
  });

  it('always names exactly one base', () => {
    const bases = (['system', 'light', 'dark'] as const).flatMap((base) =>
      [true, false].map((prefersDark) =>
        themeClassNames(preference({ base }), prefersDark).filter((c) => c.startsWith('theme-')),
      ),
    );

    for (const named of bases) {
      expect(named).toHaveLength(1);
    }
  });
});
