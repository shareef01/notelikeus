import { describe, expect, it } from 'vitest';
import { readContractFixture } from '@/lib/contract/contractFixtures';
import {
  parseThemeBlocks,
  readGlobalsCss,
  resolveThemeVars,
  rgbChannelsToHex,
} from '@/styles/__testing__/themeCss';

/**
 * contracts/design/theme-colors.json against globals.css. Kotlin ThemeTokensContractTest asserts the
 * same file against colorSchemeFor, so the same theme renders the same colours on every client.
 */
interface ThemeEntry {
  web: string[];
  colors: Record<'background' | 'surface' | 'surfaceVariant' | 'onSurface' | 'secondary' | 'muted' | 'outline', string>;
}

const contract = readContractFixture<{
  themes: Record<string, ThemeEntry>;
  lightAccentPrimary: { blue: string; green: string };
}>('design/theme-colors.json');

const WEB_VAR: Record<keyof ThemeEntry['colors'], string> = {
  background: 'background',
  surface: 'surface',
  surfaceVariant: 'surface-variant',
  onSurface: 'primary',
  secondary: 'secondary',
  muted: 'muted',
  outline: 'outline',
};

describe('contracts/design/theme-colors.json (web)', () => {
  const blocks = parseThemeBlocks(readGlobalsCss());

  it.each(Object.entries(contract.themes))('%s', (_name, theme) => {
    const vars = resolveThemeVars(blocks, theme.web);
    const actual = Object.fromEntries(
      Object.entries(WEB_VAR).map(([role, cssVar]) => {
        const channels = vars[`${cssVar}-rgb`];
        return [role, channels ? rgbChannelsToHex(channels) : 'unset'];
      }),
    );
    expect(actual).toEqual(theme.colors);
  });

  it('light accents tint the primary', () => {
    expect(rgbChannelsToHex(resolveThemeVars(blocks, ['theme-light', 'accent-blue'])['primary-rgb'] ?? '')).toBe(
      contract.lightAccentPrimary.blue,
    );
    expect(rgbChannelsToHex(resolveThemeVars(blocks, ['theme-light', 'accent-green'])['primary-rgb'] ?? '')).toBe(
      contract.lightAccentPrimary.green,
    );
  });

  it('light accents keep text-primary-rgb neutral', () => {
    expect(rgbChannelsToHex(resolveThemeVars(blocks, ['theme-light', 'accent-blue'])['text-primary-rgb'] ?? '')).toBe(
      '#151515',
    );
    expect(rgbChannelsToHex(resolveThemeVars(blocks, ['theme-light', 'accent-green'])['text-primary-rgb'] ?? '')).toBe(
      '#151515',
    );
  });
});
