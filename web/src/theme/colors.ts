/** Signed 32-bit ARGB integers — identical to Android Color.kt / Room storage. */
const argb = (hex: number) => hex | 0;

export interface NoteColorOption {
  light: number;
  dark: number;
}

/**
 * Vibrant Muted Note Palette (Modern & Visible)
 * Synchronized with Android Elite UI/UX Standards
 */
export const NOTE_COLOR_OPTIONS: NoteColorOption[] = [
  { light: 0, dark: 0 }, // Use theme default
  { light: argb(0xffffb2b2), dark: argb(0xff421a1a) },
  { light: argb(0xffffd580), dark: argb(0xff422b18) },
  { light: argb(0xfffff780), dark: argb(0xff423c18) },
  { light: argb(0xffb2ffb2), dark: argb(0xff1a421a) },
  { light: argb(0xffb2fff0), dark: argb(0xff18423f) },
  { light: argb(0xffb2d8ff), dark: argb(0xff182b42) },
  { light: argb(0xffb2beff), dark: argb(0xff1e2242) },
  { light: argb(0xffd8b2ff), dark: argb(0xff2b1a42) },
  { light: argb(0xffffb2e0), dark: argb(0xff421a33) },
  { light: argb(0xffe0c4a8), dark: argb(0xff33261a) },
  { light: argb(0xffebebeb), dark: argb(0xff262626) },
];

export function argbToCss(argb: number): string {
  if (argb === 0) return 'transparent';
  const unsigned = argb >>> 0;
  const r = (unsigned >> 16) & 0xff;
  const g = (unsigned >> 8) & 0xff;
  const b = unsigned & 0xff;
  return `rgb(${r} ${g} ${b})`;
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
