/** Signed 32-bit ARGB integers — identical to Android Color.kt / Room storage. */
const argb = (hex: number) => hex | 0;

export interface NoteColorOption {
  light: number;
  dark: number;
}

/**
 * 8 solid Material-inspired note colors (+ theme default).
 * Light: soft container tones. Dark: richer saturated surfaces that still read as color.
 */
export const NOTE_COLOR_OPTIONS: NoteColorOption[] = [
  { light: 0, dark: 0 }, // theme default
  { light: argb(0xffffcdd2), dark: argb(0xff6d2b2b) }, // Coral
  { light: argb(0xffffe0b2), dark: argb(0xff6b4520) }, // Orange
  { light: argb(0xfffff59d), dark: argb(0xff6b5c18) }, // Amber
  { light: argb(0xffc8e6c9), dark: argb(0xff2e5a32) }, // Green
  { light: argb(0xffb2dfdb), dark: argb(0xff1e5650) }, // Teal
  { light: argb(0xffbbdefb), dark: argb(0xff2a4a6e) }, // Blue
  { light: argb(0xffe1bee7), dark: argb(0xff4a2d62) }, // Purple
  { light: argb(0xfff8bbd0), dark: argb(0xff6b2d48) }, // Pink
];

export function argbToCss(argb: number): string {
  if (argb === 0) return 'transparent';
  const unsigned = argb >>> 0;
  const r = (unsigned >> 16) & 0xff;
  const g = (unsigned >> 8) & 0xff;
  const b = unsigned & 0xff;
  return `rgb(${r} ${g} ${b})`;
}

/** Same as argbToCss but with an alpha channel — use this instead of string-concatenating a hex suffix onto argbToCss's output, which isn't valid CSS. */
export function argbToCssAlpha(argb: number, alpha: number): string {
  if (argb === 0) return 'transparent';
  const unsigned = argb >>> 0;
  const r = (unsigned >> 16) & 0xff;
  const g = (unsigned >> 8) & 0xff;
  const b = unsigned & 0xff;
  return `rgb(${r} ${g} ${b} / ${alpha})`;
}

export function noteColorsForTheme(isDark: boolean): number[] {
  return NOTE_COLOR_OPTIONS.map((option) => (isDark ? option.dark : option.light));
}

export function noteColorCounterpart(argb: number): number | null {
  for (const option of NOTE_COLOR_OPTIONS) {
    if (argb === option.light) return option.dark;
    if (argb === option.dark) return option.light;
  }
  return null;
}

export function noteColorsMatch(noteArgb: number, filterArgb: number): boolean {
  return noteArgb === filterArgb || noteColorCounterpart(noteArgb) === filterArgb;
}
