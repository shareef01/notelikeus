import { describe, expect, it } from 'vitest';
import {
  buildSearchText,
  foldForSearch,
  noteMatchesSearchQuery,
  searchTokens,
} from '@/lib/text/searchText';

describe('foldForSearch', () => {
  it('folds case', () => {
    expect(foldForSearch('Hello WORLD')).toBe('hello world');
  });

  it('strips diacritics so an accented note is findable by plain letters', () => {
    expect(foldForSearch('café')).toBe('cafe');
    expect(foldForSearch('Über')).toBe('uber');
    expect(foldForSearch('naïve')).toBe('naive');
    expect(foldForSearch('Zürich')).toBe('zurich');
    expect(foldForSearch('garçon')).toBe('garcon');
    expect(foldForSearch('smörgåsbord')).toBe('smorgasbord');
  });

  it('expands ligatures and eszett rather than dropping them', () => {
    expect(foldForSearch('straße')).toBe('strasse');
    expect(foldForSearch('æther')).toBe('aether');
    expect(foldForSearch('œuvre')).toBe('oeuvre');
  });

  it('covers Latin Extended-A', () => {
    expect(foldForSearch('Łódź')).toBe('lodz');
    expect(foldForSearch('Český')).toBe('cesky');
    expect(foldForSearch('Gdańsk')).toBe('gdansk');
    expect(foldForSearch('İstanbul')).toBe('istanbul');
  });

  it('punctuation separates rather than glues', () => {
    expect(foldForSearch("don't")).toBe('don t');
    expect(foldForSearch('a---b')).toBe('a b');
    expect(foldForSearch('  hello,   world!  ')).toBe('hello world');
  });

  it('keeps digits and passes unfolded scripts through', () => {
    expect(foldForSearch('Meeting 2026 Q3')).toBe('meeting 2026 q3');
    expect(foldForSearch('Привет')).toBe('привет');
    expect(foldForSearch('日本語')).toBe('日本語');
  });

  it('tokenises in order and keeps duplicates', () => {
    expect(searchTokens('The cat the HAT')).toEqual(['the', 'cat', 'the', 'hat']);
    expect(searchTokens('!!!')).toEqual([]);
  });

  it('search text covers every field the in-memory filter looks at', () => {
    const text = buildSearchText(
      'Réunion',
      'Discuss the björk release',
      ['Buy tickets', 'Café after'],
      ['Musique'],
    );
    for (const token of ['reunion', 'bjork', 'tickets', 'cafe', 'musique']) {
      expect(text).toContain(token);
    }
  });
});

describe('noteMatchesSearchQuery', () => {
  it('matches by word prefix, not substring', () => {
    expect(noteMatchesSearchQuery('notes', 'not')).toBe(true);
    expect(noteMatchesSearchQuery('notes', 'ote')).toBe(false);
  });

  it('requires every token and ignores order', () => {
    expect(noteMatchesSearchQuery('bread and milk', 'milk bread')).toBe(true);
    expect(noteMatchesSearchQuery('bread and milk', 'milk cheese')).toBe(false);
  });

  it('treats blank queries as a match', () => {
    expect(noteMatchesSearchQuery('anything', '   ')).toBe(true);
  });
});
