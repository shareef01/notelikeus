import { describe, expect, it } from 'vitest';
import {
  prefixLinesWithBullet,
  stripMarkdownForPreview,
  toSafeHref,
  wrapSelection,
  wrapSelectionAsLink,
} from '@/lib/text/markdown';

describe('stripMarkdownForPreview', () => {
  it('drops bold, italic and bullet markers', () => {
    expect(stripMarkdownForPreview('**bold** and _italic_')).toBe('bold and italic');
    expect(stripMarkdownForPreview('• one\n• two')).toBe('one\ntwo');
  });

  it('keeps link labels and drops targets', () => {
    expect(stripMarkdownForPreview('see [docs](https://example.com/x)')).toBe('see docs');
  });

  it('leaves unmatched markers alone', () => {
    expect(stripMarkdownForPreview('2 ** 3 = 8')).toBe('2 ** 3 = 8');
  });

  it('does not strip empty marker pairs (toolbar inserts these while typing)', () => {
    expect(stripMarkdownForPreview('****')).toBe('****');
    expect(stripMarkdownForPreview('__')).toBe('__');
  });
});

describe('wrapSelection', () => {
  it('wraps the selected range and keeps it selected', () => {
    expect(wrapSelection('hello world', 6, 11, '**')).toEqual({
      text: 'hello **world**',
      selectionStart: 8,
      selectionEnd: 13,
    });
  });

  it('inserts an empty pair and places the caret inside on a collapsed selection', () => {
    expect(wrapSelection('ab', 1, 1, '_')).toEqual({
      text: 'a__b',
      selectionStart: 2,
      selectionEnd: 2,
    });
  });

  it('normalizes a reversed selection', () => {
    expect(wrapSelection('hello world', 11, 6, '**')).toEqual({
      text: 'hello **world**',
      selectionStart: 8,
      selectionEnd: 13,
    });
  });
});

describe('prefixLinesWithBullet', () => {
  it('bullets every line the selection touches', () => {
    const result = prefixLinesWithBullet('one\ntwo\nthree', 1, 5);
    expect(result.text).toBe('• one\n• two\nthree');
    expect(result.text.slice(result.selectionStart, result.selectionEnd)).toBe('• one\n• two');
  });

  it('skips blank lines and lines that are already bulleted', () => {
    expect(prefixLinesWithBullet('• one\n\ntwo', 0, 10).text).toBe('• one\n\n• two');
  });

  it('bullets the final line when the selection has no trailing newline', () => {
    expect(prefixLinesWithBullet('one\ntwo', 5, 5).text).toBe('one\n• two');
  });
});

describe('toSafeHref', () => {
  it('passes through http, https and mailto', () => {
    expect(toSafeHref('https://example.com')).toBe('https://example.com');
    expect(toSafeHref('  http://example.com  ')).toBe('http://example.com');
    expect(toSafeHref('MAILTO:a@b.com')).toBe('MAILTO:a@b.com');
  });

  it('assumes https for bare domains and paths', () => {
    expect(toSafeHref('example.com/notes')).toBe('https://example.com/notes');
  });

  it('rejects any other scheme', () => {
    expect(toSafeHref('javascript:alert(1)')).toBe('#');
    expect(toSafeHref('data:text/html,<script>')).toBe('#');
    expect(toSafeHref('vbscript:msgbox')).toBe('#');
    expect(toSafeHref(' JaVaScRiPt:alert(1)')).toBe('#');
  });
});

describe('wrapSelectionAsLink', () => {
  it('wraps the selection and puts the caret after the link', () => {
    const result = wrapSelectionAsLink('see docs', 4, 8, 'example.com');
    expect(result).toEqual({
      text: 'see [docs](https://example.com)',
      selectionStart: 31,
      selectionEnd: 31,
    });
  });

  it('inserts a selected placeholder label on a collapsed selection', () => {
    const result = wrapSelectionAsLink('see ', 4, 4, 'https://example.com');
    expect(result?.text).toBe('see [link](https://example.com)');
    expect(result?.text.slice(result.selectionStart, result.selectionEnd)).toBe('link');
  });

  it('returns null for a blank url', () => {
    expect(wrapSelectionAsLink('see docs', 4, 8, '   ')).toBeNull();
  });

  it('neutralizes an unsafe scheme instead of emitting it as an href', () => {
    expect(wrapSelectionAsLink('x', 0, 1, 'javascript:alert(1)')?.text).toBe('[x](#)');
  });
});
