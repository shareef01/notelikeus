import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Chrome text against every surface, for every theme class combination globals.css defines.
 * The web twin of Kotlin ThemeTextContrastTest: the muted role is drawn on the background, on
 * cards and on inputs (surface-variant), so it has to clear WCAG AA on all three.
 */
// vitest runs from web/ (CI and package scripts); happy-dom's import.meta.url is not a file URL.
const css = readFileSync(resolve(process.cwd(), 'src/styles/globals.css'), 'utf8');

interface Block {
  classes: string[];
  vars: Record<string, string>;
  order: number;
}

function parseBlocks(): Block[] {
  const blocks: Block[] = [];
  const pattern = /^((?:\.[a-z-]+)+)\s*\{([^}]*)\}/gm;
  let match: RegExpExecArray | null;
  let order = 0;
  while ((match = pattern.exec(css)) !== null) {
    const classes = match[1].split('.').filter(Boolean);
    if (!classes.some((c) => c.startsWith('theme-'))) continue;
    const vars: Record<string, string> = {};
    for (const decl of match[2].matchAll(/--([a-z-]+-rgb):\s*([\d\s]+);/g)) vars[decl[1]] = decl[2].trim();
    blocks.push({ classes, vars, order: order++ });
  }
  return blocks;
}

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
  const blocks = parseBlocks();

  it('parses the theme blocks', () => {
    expect(blocks.length).toBeGreaterThanOrEqual(6);
  });

  for (const target of blocks) {
    const name = target.classes.join('.');
    it(`${name}: primary and muted text clear 4.5:1 on background, surface and surface-variant`, () => {
      // Cascade: every block whose classes are a subset applies, fewer classes first, then source order.
      const applicable = blocks
        .filter((b) => b.classes.every((c) => target.classes.includes(c)))
        .sort((a, b) => a.classes.length - b.classes.length || a.order - b.order);
      const vars = Object.assign({}, ...applicable.map((b) => b.vars)) as Record<string, string>;
      const failures: string[] = [];
      for (const text of ['primary', 'muted']) {
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
