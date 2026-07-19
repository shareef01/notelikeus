import { argbToCss } from './colors';

const LIGHT_TEXT = '#121212';
const DARK_TEXT = '#FFFFFF';
const LUMINANCE_THRESHOLD = 0.45;

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

export function contentColorForBackground(argb: number): string {
  if (argb === 0) return 'inherit';
  return relativeLuminance(argb) > LUMINANCE_THRESHOLD ? LIGHT_TEXT : DARK_TEXT;
}

export function noteSurfaceStyle(
  argb: number,
  options?: { solid?: boolean },
): { backgroundColor: string; color: string } {
  if (argb === 0) {
    return {
      // Cards use a faint wash on the page; dialogs/shells need an opaque fill
      // so content behind them (notes board, filters) does not bleed through.
      backgroundColor: options?.solid
        ? 'rgb(var(--surface-rgb))'
        : 'rgba(255, 255, 255, 0.05)',
      color: 'rgb(var(--primary-rgb))',
    };
  }
  return {
    backgroundColor: argbToCss(argb),
    color: contentColorForBackground(argb),
  };
}
