import { describe, expect, it } from 'vitest';
import { NOTE_COLOR_OPTIONS } from './colors';
import {
  NOTE_EMPHASIS,
  contentColorForBackground,
  contrastRatio,
  relativeLuminance,
} from './contrast';

/** WCAG AA for body text. */
const AA_TEXT = 4.5;
/** WCAG AA for large text and meaningful non-text graphics. */
const AA_GRAPHIC = 3.0;

const WHITE = 0xffffffff;
const NEAR_BLACK = 0xff121212;

function rgb(argb: number) {
  const u = argb >>> 0;
  return [(u >> 16) & 0xff, (u >> 8) & 0xff, u & 0xff] as const;
}

/** What the user sees when `fg` is drawn at `alpha` over `bg`. */
function composite(fg: number, bg: number, alpha: number): number {
  const [fr, fg_, fb] = rgb(fg);
  const [br, bg_, bb] = rgb(bg);
  const mix = (f: number, b: number) => Math.round(f * alpha + b * (1 - alpha));
  return (0xff << 24) | (mix(fr, br) << 16) | (mix(fg_, bg_) << 8) | mix(fb, bb);
}

function ratioOn(container: number, foreground: number, alpha: number): number {
  return contrastRatio(
    relativeLuminance(composite(foreground, container, alpha)),
    relativeLuminance(container),
  );
}

/** Every built-in container, in both its light and dark variant. */
const containers = NOTE_COLOR_OPTIONS.flatMap((option) =>
  [option.light, option.dark].filter((c) => c !== 0),
).map((argb) => ({
  argb,
  foreground: contentColorForBackground(argb) === '#FFFFFF' ? WHITE : NEAR_BLACK,
}));

describe('note content contrast', () => {
  it('covers every built-in container', () => {
    // 8 colours x light and dark. Fails loudly if the palette grows and this does not.
    expect(containers).toHaveLength(16);
  });

  it('picks the more legible foreground, across the whole luminance range', () => {
    // This is the property the old 0.45 threshold broke, and it only shows away from the
    // palette -- which is polarised, so almost any threshold looks right on it.
    for (let step = 0; step <= 255; step += 1) {
      const grey = (0xff << 24) | (step << 16) | (step << 8) | step;
      const chosen = contentColorForBackground(grey) === '#FFFFFF' ? WHITE : NEAR_BLACK;
      const rejected = chosen === WHITE ? NEAR_BLACK : WHITE;
      const l = relativeLuminance(grey);
      expect(contrastRatio(l, relativeLuminance(chosen))).toBeGreaterThanOrEqual(
        contrastRatio(l, relativeLuminance(rejected)),
      );
    }
  });

  it('clears AA for full-opacity text on every container', () => {
    for (const { argb, foreground } of containers) {
      expect(ratioOn(argb, foreground, NOTE_EMPHASIS.full)).toBeGreaterThanOrEqual(AA_TEXT);
    }
  });

  it('clears AA for secondary text on every container', () => {
    for (const { argb, foreground } of containers) {
      const ratio = ratioOn(argb, foreground, NOTE_EMPHASIS.secondary);
      expect(
        ratio,
        `secondary text is ${ratio.toFixed(2)}:1 on ${argb.toString(16)}`,
      ).toBeGreaterThanOrEqual(AA_TEXT);
    }
  });

  it('clears the 3:1 graphics threshold for icons on every container', () => {
    for (const { argb, foreground } of containers) {
      expect(ratioOn(argb, foreground, NOTE_EMPHASIS.icon)).toBeGreaterThanOrEqual(AA_GRAPHIC);
    }
  });

  it('keeps no text tier below the solved minimum opacity', () => {
    // 0.75 is where 4.5:1 stops holding on the lightest dark container. If someone adds a
    // quieter tier for a subtler timestamp, this says why it cannot work.
    for (const tier of [NOTE_EMPHASIS.full, NOTE_EMPHASIS.secondary]) {
      expect(tier).toBeGreaterThanOrEqual(0.75);
    }
  });

  it('keeps the decorative tier far below anything readable', () => {
    expect(NOTE_EMPHASIS.decorative).toBeLessThan(0.3);
  });
});
