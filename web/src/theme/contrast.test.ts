import { describe, expect, it } from 'vitest';
import { contentColorForBackground, noteSurfaceStyle, relativeLuminance } from '@/theme/contrast';
import { NOTE_COLOR_OPTIONS, argbToCss } from '@/theme/colors';

const WHITE = 0xffffffff | 0;
const BLACK = 0xff000000 | 0;

describe('relativeLuminance', () => {
  it('spans 0 for black to 1 for white', () => {
    expect(relativeLuminance(BLACK)).toBe(0);
    expect(relativeLuminance(WHITE)).toBeCloseTo(1, 5);
  });

  it('ignores the alpha channel', () => {
    expect(relativeLuminance(0x00ffffff)).toBeCloseTo(relativeLuminance(WHITE), 10);
  });

  it('ranks green above red above blue', () => {
    const red = relativeLuminance(0xffff0000 | 0);
    const green = relativeLuminance(0xff00ff00 | 0);
    const blue = relativeLuminance(0xff0000ff | 0);
    expect(green).toBeGreaterThan(red);
    expect(red).toBeGreaterThan(blue);
  });
});

describe('contentColorForBackground', () => {
  it('inherits on the theme-default color', () => {
    expect(contentColorForBackground(0)).toBe('inherit');
  });

  it('picks dark text on light surfaces and light text on dark ones', () => {
    expect(contentColorForBackground(WHITE)).toBe('#121212');
    expect(contentColorForBackground(BLACK)).toBe('#FFFFFF');
  });

  it('keeps every palette swatch legible', () => {
    for (const option of NOTE_COLOR_OPTIONS.slice(1)) {
      expect(contentColorForBackground(option.light)).toBe('#121212');
      expect(contentColorForBackground(option.dark)).toBe('#FFFFFF');
    }
  });
});

describe('noteSurfaceStyle', () => {
  it('uses theme variables for the default color', () => {
    expect(noteSurfaceStyle(0)).toEqual({
      backgroundColor: 'rgb(var(--surface-rgb))',
      color: 'rgb(var(--primary-rgb))',
    });
  });

  it('renders the stored color as-is when no palette is given', () => {
    const coral = NOTE_COLOR_OPTIONS[1].light;
    expect(noteSurfaceStyle(coral)).toEqual({
      backgroundColor: argbToCss(coral),
      color: '#121212',
    });
  });

  it('swaps a light swatch to its dark counterpart for a dark palette', () => {
    const { light, dark } = NOTE_COLOR_OPTIONS[1];
    expect(noteSurfaceStyle(light, { isDarkPalette: true })).toEqual({
      backgroundColor: argbToCss(dark),
      color: '#FFFFFF',
    });
    expect(noteSurfaceStyle(dark, { isDarkPalette: false })).toEqual({
      backgroundColor: argbToCss(light),
      color: '#121212',
    });
  });
});
