import { describe, expect, it } from 'vitest';
import { parseThemeBlocks, readGlobalsCss, resolveThemeVars } from '@/styles/__testing__/themeCss';

/**
 * Chrome text against every surface, for every theme class combination globals.css defines.
 * The web twin of Kotlin ThemeTextContrastTest: the muted role is drawn on the background, on
 * cards and on inputs (surface-variant), so it has to clear WCAG AA on all three.
 */
function luminance(rgb: string): number {
  const [r, g, b] = rgb.split(/\s+/).map((v) => {
    const c = Number(v) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('theme text contrast (globals.css)', () => {
  const blocks = parseThemeBlocks(readGlobalsCss());

  it('parses the theme blocks', () => {
    expect(blocks.length).toBeGreaterThanOrEqual(6);
  });

  for (const target of blocks) {
    const name = target.classes.join('.');
    it(`${name}: primary, secondary and muted text clear 4.5:1 on background, surface and surface-variant`, () => {
      const vars = resolveThemeVars(blocks, target.classes);
      const failures: string[] = [];
      for (const text of ['primary', 'secondary', 'muted']) {
        for (const surface of ['background', 'surface', 'surface-variant']) {
          const fg = vars[`${text}-rgb`];
          const bg = vars[`${surface}-rgb`];
          if (!fg || !bg) continue;
          const ratio = contrast(fg, bg);
          if (ratio < 4.5) failures.push(`${text} on ${surface} = ${ratio.toFixed(2)}`);
        }
      }
      expect(failures).toEqual([]);
    });
  }
});
