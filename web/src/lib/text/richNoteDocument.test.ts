import { describe, expect, it } from 'vitest';
import { parseNoteDocument, serializeNoteDocument } from './richNoteDocument';

describe('native-compatible rich note document', () => {
  it.each(['plain\n\nsecond\n', '** with spaces **', '_ space _', 'some_legacy_italics', '**bold** and _italic_', '**_both_**', '• one\n• **two**\nafter', '[words](https://example.com)', '<script>alert(1)</script>', '# heading\n`literal code`\n&amp;'])('preserves supported text: %s', text => {
    expect(serializeNoteDocument(parseNoteDocument(text))).toBe(text);
  });
  it('closes inline marks across a soft line break', () => {
    const document = { type: 'doc', content: [{ type: 'paragraph', content: [
      { type: 'text', text: 'first', marks: [{ type: 'bold' }] },
      { type: 'hardBreak' },
      { type: 'text', text: 'second', marks: [{ type: 'bold' }] },
    ] }] };
    expect(serializeNoteDocument(document)).toBe('**first**\n**second**');
  });
  it('keeps unsafe links as literal text', () => {
    const text = '[bad](javascript:alert(1))';
    expect(JSON.stringify(parseNoteDocument(text))).not.toContain('"type":"link"');
    expect(serializeNoteDocument(parseNoteDocument(text))).toBe(text);
  });
  it('keeps continuous marks across adjacent text runs', () => {
    expect(serializeNoteDocument({ type: 'doc', content: [{ type: 'paragraph', content: [
      { type: 'text', text: 'one ', marks: [{ type: 'bold' }] },
      { type: 'text', text: 'two', marks: [{ type: 'bold' }, { type: 'italic' }] },
    ] }] })).toBe('**one _two_**');
  });
});
