import { describe, expect, it } from 'vitest';
import {
  NOTE_COLOR_NAMES,
  NOTE_COLOR_OPTIONS,
  argbToCss,
  argbToCssAlpha,
  noteColorCounterpart,
  noteColorForTheme,
  noteColorsForTheme,
  noteColorsMatch,
} from '@/theme/colors';

describe('palette', () => {
  it('names every option', () => {
    expect(NOTE_COLOR_NAMES).toHaveLength(NOTE_COLOR_OPTIONS.length);
  });

  it('projects one color per option for the active palette', () => {
    expect(noteColorsForTheme(true)).toEqual(NOTE_COLOR_OPTIONS.map((o) => o.dark));
    expect(noteColorsForTheme(false)).toEqual(NOTE_COLOR_OPTIONS.map((o) => o.light));
  });
});

describe('argbToCss', () => {
  it('renders signed ARGB integers as rgb()', () => {
    expect(argbToCss(0xff112233 | 0)).toBe('rgb(17 34 51)');
    expect(argbToCssAlpha(0xff112233 | 0, 0.5)).toBe('rgb(17 34 51 / 0.5)');
  });

  it('treats the default color as transparent', () => {
    expect(argbToCss(0)).toBe('transparent');
    expect(argbToCssAlpha(0, 0.5)).toBe('transparent');
  });
});

describe('noteColorForTheme', () => {
  it('keeps the default color', () => {
    expect(noteColorForTheme(0, true)).toBe(0);
  });

  it('maps either half of a pair to the active palette', () => {
    const { light, dark } = NOTE_COLOR_OPTIONS[4];
    expect(noteColorForTheme(light, true)).toBe(dark);
    expect(noteColorForTheme(dark, false)).toBe(light);
    expect(noteColorForTheme(light, false)).toBe(light);
  });

  it('passes an unknown color through unchanged', () => {
    const custom = 0xff123456 | 0;
    expect(noteColorForTheme(custom, true)).toBe(custom);
  });
});

describe('noteColorCounterpart', () => {
  it('pairs light and dark swatches', () => {
    const { light, dark } = NOTE_COLOR_OPTIONS[2];
    expect(noteColorCounterpart(light)).toBe(dark);
    expect(noteColorCounterpart(dark)).toBe(light);
  });

  it('returns null for colors outside the palette', () => {
    expect(noteColorCounterpart(0xff123456 | 0)).toBeNull();
  });
});

describe('noteColorsMatch', () => {
  it('matches a note stored in either palette against the filter swatch', () => {
    const { light, dark } = NOTE_COLOR_OPTIONS[6];
    expect(noteColorsMatch(dark, light)).toBe(true);
    expect(noteColorsMatch(light, light)).toBe(true);
  });

  it('does not match a different swatch', () => {
    expect(noteColorsMatch(NOTE_COLOR_OPTIONS[6].light, NOTE_COLOR_OPTIONS[7].light)).toBe(false);
  });
});
