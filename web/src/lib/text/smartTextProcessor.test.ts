import { describe, expect, it } from 'vitest';
import { processSmartText, type TextEdit } from '@/lib/text/smartTextProcessor';

function edit(text: string, caret = text.length): TextEdit {
  return { text, selectionStart: caret, selectionEnd: caret };
}

/** Simulates typing `typed` at the caret of `previous`. */
function type(previous: TextEdit, typed: string): TextEdit {
  const text =
    previous.text.slice(0, previous.selectionStart) +
    typed +
    previous.text.slice(previous.selectionEnd);
  return edit(text, previous.selectionStart + typed.length);
}

describe('processSmartText', () => {
  it('passes deletions through untouched', () => {
    const current = edit('ab');
    expect(processSmartText(current, edit('abc'))).toEqual({ edit: current });
  });

  it('converts "* " and "- " at a line start into a bullet', () => {
    for (const marker of ['*', '-']) {
      const previous = edit(marker);
      const result = processSmartText(type(previous, ' '), previous);
      expect(result.edit).toEqual({ text: '• ', selectionStart: 2, selectionEnd: 2 });
    }
  });

  it('converts a marker on a later line without touching earlier lines', () => {
    const previous = edit('one\n*');
    const result = processSmartText(type(previous, ' '), previous);
    expect(result.edit.text).toBe('one\n• ');
    expect(result.edit.selectionStart).toBe(6);
  });

  it('leaves a mid-line asterisk alone', () => {
    const previous = edit('a *');
    const current = type(previous, ' ');
    expect(processSmartText(current, previous)).toEqual({ edit: current });
  });

  it('flags checkbox shorthand as a structure change', () => {
    for (const shorthand of ['[]', '[ ]']) {
      const previous = edit(shorthand);
      const current = type(previous, ' ');
      expect(processSmartText(current, previous)).toEqual({ edit: current, structureChanged: true });
    }
  });

  it('continues a bullet list on Enter', () => {
    const previous = edit('• milk');
    const result = processSmartText(type(previous, '\n'), previous);
    expect(result.edit).toEqual({
      text: '• milk\n• ',
      selectionStart: 9,
      selectionEnd: 9,
    });
  });

  it('ends the list when Enter is pressed on an empty bullet', () => {
    const previous = edit('• milk\n• ');
    const result = processSmartText(type(previous, '\n'), previous);
    expect(result.edit).toEqual({
      text: '• milk\n\n',
      selectionStart: 8,
      selectionEnd: 8,
    });
  });

  it('does not add a bullet after a plain line', () => {
    const previous = edit('milk');
    const current = type(previous, '\n');
    expect(processSmartText(current, previous)).toEqual({ edit: current });
  });

  it('preserves text after the caret when continuing a bullet mid-note', () => {
    const previous = { text: '• milk\ntail', selectionStart: 6, selectionEnd: 6 };
    const result = processSmartText(type(previous, '\n'), previous);
    expect(result.edit.text).toBe('• milk\n• \ntail');
  });
});
