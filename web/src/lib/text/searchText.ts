/**
 * Search normalisation matching Kotlin `domain.query.SearchText`.
 *
 * Token-prefix + diacritic fold, not mid-word `includes()`. "ote" must not find "notes";
 * "cafe" must find "café". The fold table is copied from commonMain rather than using
 * String.normalize, so a Latin-1 / Extended-A note matches the same way on every client.
 */

function foldChar(code: number): string {
  if ((code >= 0xc0 && code <= 0xc5) || (code >= 0xe0 && code <= 0xe5)) return 'a';
  if (code === 0xc6 || code === 0xe6) return 'ae';
  if (code === 0xc7 || code === 0xe7) return 'c';
  if ((code >= 0xc8 && code <= 0xcb) || (code >= 0xe8 && code <= 0xeb)) return 'e';
  if ((code >= 0xcc && code <= 0xcf) || (code >= 0xec && code <= 0xef)) return 'i';
  if (code === 0xd0 || code === 0xf0) return 'd';
  if (code === 0xd1 || code === 0xf1) return 'n';
  if (
    (code >= 0xd2 && code <= 0xd6) ||
    (code >= 0xf2 && code <= 0xf6) ||
    code === 0xd8 ||
    code === 0xf8
  ) {
    return 'o';
  }
  if ((code >= 0xd9 && code <= 0xdc) || (code >= 0xf9 && code <= 0xfc)) return 'u';
  if (code === 0xdd || code === 0xfd || code === 0xff) return 'y';
  if (code === 0xde || code === 0xfe) return 'th';
  if (code === 0xdf) return 'ss';

  if (code >= 0x100 && code <= 0x105) return 'a';
  if (code >= 0x106 && code <= 0x10d) return 'c';
  if (code >= 0x10e && code <= 0x111) return 'd';
  if (code >= 0x112 && code <= 0x11b) return 'e';
  if (code >= 0x11c && code <= 0x123) return 'g';
  if (code >= 0x124 && code <= 0x127) return 'h';
  if (code >= 0x128 && code <= 0x131) return 'i';
  if (code === 0x132 || code === 0x133) return 'ij';
  if (code === 0x134 || code === 0x135) return 'j';
  if (code >= 0x136 && code <= 0x138) return 'k';
  if (code >= 0x139 && code <= 0x142) return 'l';
  if (code >= 0x143 && code <= 0x14b) return 'n';
  if (code >= 0x14c && code <= 0x151) return 'o';
  if (code === 0x152 || code === 0x153) return 'oe';
  if (code >= 0x154 && code <= 0x159) return 'r';
  if (code >= 0x15a && code <= 0x161) return 's';
  if (code >= 0x162 && code <= 0x167) return 't';
  if (code >= 0x168 && code <= 0x173) return 'u';
  if (code === 0x174 || code === 0x175) return 'w';
  if (code >= 0x176 && code <= 0x178) return 'y';
  if (code >= 0x179 && code <= 0x17e) return 'z';
  if (code === 0x17f) return 's';

  return String.fromCodePoint(code).toLowerCase();
}

function isLetterOrDigit(ch: string): boolean {
  return /\p{L}|\p{N}/u.test(ch);
}

/** Lower case, diacritics stripped, punctuation collapsed to single spaces. */
export function foldForSearch(input: string): string {
  let out = '';
  let pendingSpace = false;
  for (const raw of input) {
    const folded = foldChar(raw.codePointAt(0) ?? 0);
    const isWord = folded.length > 0 && [...folded].every(isLetterOrDigit);
    if (folded.length === 0 || !isWord) {
      if (out.length > 0) pendingSpace = true;
      continue;
    }
    if (pendingSpace) {
      out += ' ';
      pendingSpace = false;
    }
    out += folded;
  }
  return out;
}

export function searchTokens(input: string): string[] {
  return foldForSearch(input).split(' ').filter((token) => token.length > 0);
}

export function buildSearchText(
  title: string,
  content: string,
  checklistTexts: string[],
  labelNames: string[],
): string {
  const parts = [title, content, ...checklistTexts, ...labelNames].filter((part) => part.trim());
  return foldForSearch(parts.join(' '));
}

/**
 * Every query token must be a word-prefix somewhere in the note, in any order.
 * Mirrors Kotlin `NoteQueryMatcher.matchesText`.
 */
export function noteMatchesSearchQuery(
  haystack: string,
  query: string,
): boolean {
  const needles = searchTokens(query);
  if (needles.length === 0) return true;
  return needles.every((token) => haystack.startsWith(token) || haystack.includes(` ${token}`));
}
