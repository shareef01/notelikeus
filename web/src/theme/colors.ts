/** Signed 32-bit ARGB integers — identical to Android Color.kt / Room storage. */
const argb = (hex: number) => hex | 0;

export interface NoteColorOption {
  light: number;
  dark: number;
}

/**
 * Premium Desaturated Dark-Mode Palette
 * Synchronized with Android Elite UI/UX Standards
 */
export const NOTE_COLOR_OPTIONS: NoteColorOption[] = [
  { light: argb(0xfff7f7f7), dark: argb(0xff121212) },
  { light: argb(0xffffdada), dark: argb(0xff2d1616) },
  { light: argb(0xffffe5c0), dark: argb(0xff2d2014) },
  { light: argb(0xfffff9c0), dark: argb(0xff2d2b14) },
  { light: argb(0xffd4ffd4), dark: argb(0xff162d16) },
  { light: argb(0xffd4fff9), dark: argb(0xff142d2b) },
  { light: argb(0xffd4e8ff), dark: argb(0xff141f2d) },
  { light: argb(0xffd4dcff), dark: argb(0xff181c2d) },
  { light: argb(0xffe8d4ff), dark: argb(0xff20162d) },
  { light: argb(0xffffd4ec), dark: argb(0xff2d1624) },
  { light: argb(0xffe8dac0), dark: argb(0xff211b14) },
  { light: argb(0xffeeeeee), dark: argb(0xff1a1a1a) },
];

export function argbToCss(argb: number): string {
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
