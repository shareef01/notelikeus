import { describe, expect, it } from 'vitest';
import { toThemePreference, type LegacyAppTheme, type ThemePreference } from './settingsStore';

/**
 * A direct mirror of `AppThemeMigrationTest` on the Kotlin side.
 *
 * The point of the split is that a user with both clients sees the same settings and gets the same
 * result, so the migration has to agree with `AppTheme.toThemePreference` case for case. If the two
 * ever disagree, one of them is silently changing somebody's theme.
 */
describe('toThemePreference', () => {
  const cases: Array<[LegacyAppTheme, ThemePreference]> = [
    ['auto', { base: 'system', amoled: false, accent: 'neutral' }],
    ['light', { base: 'light', amoled: false, accent: 'neutral' }],
    ['dark', { base: 'dark', amoled: false, accent: 'neutral' }],
    // These three were a black level or a hue, so they carry their own.
    ['true_dark', { base: 'dark', amoled: true, accent: 'neutral' }],
    ['midnight', { base: 'dark', amoled: false, accent: 'blue' }],
    ['forest', { base: 'dark', amoled: false, accent: 'green' }],
  ];

  it.each(cases)('resolves %s the way the Kotlin clients do', (stored, expected) => {
    expect(toThemePreference(stored)).toEqual(expected);
  });

  it('lets auto, light and dark defer to the separately stored accent and black level', () => {
    expect(toThemePreference('auto', true, 'green')).toEqual({
      base: 'system',
      amoled: true,
      accent: 'green',
    });
    expect(toThemePreference('dark', true, 'blue')).toEqual({
      base: 'dark',
      amoled: true,
      accent: 'blue',
    });
  });

  /**
   * Midnight was never "dark plus a blue you chose" — it was blue. Deferring here would let a
   * stored accent silently repaint a named theme during the migration.
   */
  it('does not let a stored accent override a theme that was itself a hue', () => {
    expect(toThemePreference('midnight', false, 'green').accent).toBe('blue');
    expect(toThemePreference('forest', false, 'blue').accent).toBe('green');
  });

  it('does not let a stored black level override the theme that was itself a black level', () => {
    expect(toThemePreference('true_dark', false, 'neutral').amoled).toBe(true);
  });

  it('never resolves a legacy theme to a light base except light itself', () => {
    for (const [stored, expected] of cases) {
      if (stored === 'light') continue;
      expect(expected.base, `${stored} should not be light`).not.toBe('light');
    }
  });
});
