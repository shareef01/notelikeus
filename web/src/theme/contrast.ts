import { argbToCss, noteColorForTheme } from './colors';

const LIGHT_TEXT = '#121212';
const DARK_TEXT = '#FFFFFF';

/**
 * Opacity tiers for content drawn on a note.
 *
 * Mirrors NoteEmphasis in the Kotlin client, and exists for the same measured reason. Across the
 * sixteen built-in containers plus the theme surfaces, text at 0.55 alpha bottoms out at 3.24:1
 * and fails on most of them; 0.70 still fails on the lightest dark container; 0.80 passes
 * everywhere. The solved minimum for 4.5:1 is 0.75, so SECONDARY takes 0.80 for headroom, and
 * ICON takes 0.60 against the 3:1 that WCAG asks of non-text graphics (solved minimum 0.51).
 *
 * The consequence is the same here as there: on a coloured note there is exactly one legible
 * de-emphasis step for text. Hierarchy below it has to come from size, weight or position.
 */
export const NOTE_EMPHASIS = {
  full: 1,
  secondary: 0.8,
  icon: 0.6,
  /** Chip fills, dividers, hairlines. Never text. */
  decorative: 0.14,
} as const;

function channel(value: number): number {
  const srgb = value / 255;
  return srgb <= 0.03928 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4;
}

/** Relative luminance — mirrors Android Color.luminance() used in getContentColor(). */
export function relativeLuminance(argb: number): number {
  const unsigned = argb >>> 0;
  const r = channel((unsigned >> 16) & 0xff);
  const g = channel((unsigned >> 8) & 0xff);
  const b = channel(unsigned & 0xff);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG contrast ratio between two relative luminances. */
export function contrastRatio(a: number, b: number): number {
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Picks whichever foreground actually reads better on this background, by measuring both.
 *
 * This used to be `relativeLuminance(argb) > 0.45`, which is not where the two candidates cross
 * over: white and #121212 tie at a luminance of about **0.19**, so every background between 0.19
 * and 0.45 was given white text when near-black was the more legible choice. A mid-tone at 0.40
 * reads around 2.3:1 in white, against roughly 8:1 in near-black.
 *
 * None of the built-in palette falls in that band -- it is polarised -- so this changes nothing
 * for the nine built-in colours. What reaches the band is arbitrary colour: `color` is a plain
 * ARGB int in the cloud document and the backup format, so an imported note, or one written
 * by another client, can carry any value. Measuring rather than thresholding gives those a
 * readable foreground instead of an accidental one.
 *
 * The Kotlin client fixed this in getContentColor(); the web copy kept the threshold. Same bug,
 * two implementations -- which is the cost of the two clients sharing a data model but not code.
 */
export function contentColorForBackground(argb: number): string {
  if (argb === 0) return 'inherit';
  const background = relativeLuminance(argb);
  const whiteContrast = contrastRatio(background, relativeLuminance(0xffffffff));
  const darkContrast = contrastRatio(background, relativeLuminance(0xff121212));
  return whiteContrast >= darkContrast ? DARK_TEXT : LIGHT_TEXT;
}

export function noteSurfaceStyle(
  argb: number,
  options?: { solid?: boolean; isDarkPalette?: boolean },
): { backgroundColor: string; color: string } {
  const resolved =
    options?.isDarkPalette === undefined ? argb : noteColorForTheme(argb, options.isDarkPalette);

  if (resolved === 0) {
    return {
      // Opaque surface so default cards read cleanly on light and dark chrome.
      backgroundColor: 'rgb(var(--surface-rgb))',
      color: 'rgb(var(--primary-rgb))',
    };
  }
  return {
    backgroundColor: argbToCss(resolved),
    color: contentColorForBackground(resolved),
  };
}
