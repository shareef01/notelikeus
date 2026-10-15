import { describe, expect, it } from 'vitest';
import { referenceFoldForSearch } from '@/lib/text/__fixtures__/referenceFold';
import { foldForSearch, memoFolded } from '@/lib/text/searchText';

const SAMPLES = [
  '', '   ', 'Hello, World!', 'Café crème brûlée', 'ÆON Œuvre straße', 'naïve — résumé… 42%',
  'Ĳssel Łódź Žižkov', 'emoji 🎉 between', '日本語のノート', 'tabs\tand\nnewlines', 'MiXeD_case-123',
  'ſ long s', 'Ελληνικά ΚΕΙΜΕΝΟ', '"quoted" (parens) [brackets] {braces}',
];

describe('foldForSearch ASCII fast path', () => {
  it('matches the original fold on fixed samples', () => {
    for (const sample of SAMPLES) expect(foldForSearch(sample)).toBe(referenceFoldForSearch(sample));
  });

  it('matches the original fold on random mixed text', () => {
    const alphabet = [...'aZ09 .,-_\t\nÀéÿĀŒſß日🎉Ω\'"'];
    let seed = 11;
    const rand = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
    for (let i = 0; i < 2000; i++) {
      const text = Array.from({ length: Math.floor(rand() * 40) }, () => alphabet[Math.floor(rand() * alphabet.length)]).join('');
      expect(foldForSearch(text)).toBe(referenceFoldForSearch(text));
    }
  });
});

describe('memoFolded', () => {
  it('computes once per object and field, and a new object recomputes', () => {
    let calls = 0;
    const note = {};
    expect(memoFolded(note, 'f', () => `v${++calls}`)).toBe('v1');
    expect(memoFolded(note, 'f', () => `v${++calls}`)).toBe('v1');
    expect(memoFolded({}, 'f', () => `v${++calls}`)).toBe('v2');
  });
});
