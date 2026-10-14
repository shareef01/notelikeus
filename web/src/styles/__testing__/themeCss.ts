import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Reads the theme classes of globals.css the way the browser cascades them, for tests that pin
 * colours (themeTokens.test.ts) or check contrast (themeContrast.test.ts).
 */
export interface ThemeBlock {
  classes: string[];
  vars: Record<string, string>;
  order: number;
}

/** vitest runs from web/ (CI and package scripts); happy-dom's import.meta.url is not a file URL. */
export function readGlobalsCss(): string {
  return readFileSync(resolve(process.cwd(), 'src/styles/globals.css'), 'utf8');
}

export function parseThemeBlocks(css: string): ThemeBlock[] {
  const blocks: ThemeBlock[] = [];
  // A rule may list several class selectors ("a.b,\n.a.c { … }"); each applies on its own.
  const pattern = /^((?:\.[a-z-]+)+(?:\s*,\s*(?:\.[a-z-]+)+)*)\s*\{([^}]*)\}/gm;
  let match: RegExpExecArray | null;
  let order = 0;
  while ((match = pattern.exec(css)) !== null) {
    const vars: Record<string, string> = {};
    for (const decl of match[2].matchAll(/--([a-z-]+-rgb):\s*([\d\s]+);/g)) vars[decl[1]] = decl[2].trim();
    for (const selector of match[1].split(',')) {
      const classes = selector.trim().split('.').filter(Boolean);
      if (!classes.some((c) => c.startsWith('theme-'))) continue;
      blocks.push({ classes, vars, order });
    }
    order += 1;
  }
  return blocks;
}

/**
 * The variables in effect on an element carrying [classes]: every block whose classes are a subset
 * applies, fewer classes (lower specificity) first, then source order.
 */
export function resolveThemeVars(blocks: readonly ThemeBlock[], classes: readonly string[]): Record<string, string> {
  const applicable = blocks
    .filter((block) => block.classes.every((c) => classes.includes(c)))
    .sort((a, b) => a.classes.length - b.classes.length || a.order - b.order);
  return Object.assign({}, ...applicable.map((block) => block.vars)) as Record<string, string>;
}

/** "17 17 17" → "#111111". */
export function rgbChannelsToHex(channels: string): string {
  return (
    '#' +
    channels
      .trim()
      .split(/\s+/)
      .map((n) => Number(n).toString(16).padStart(2, '0'))
      .join('')
      .toUpperCase()
  );
}
