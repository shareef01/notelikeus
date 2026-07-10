export interface TextEdit {
  text: string;
  selectionStart: number;
  selectionEnd: number;
}

export interface SmartTextResult {
  edit: TextEdit;
  structureChanged?: boolean;
}

function replaceRange(text: string, start: number, end: number, replacement: string) {
  return text.substring(0, start) + replacement + text.substring(end);
}

/** Auto bullets and list continuation — mirrors Android SmartTextProcessor. */
export function processSmartText(current: TextEdit, previous: TextEdit): SmartTextResult {
  if (current.text.length <= previous.text.length) {
    return { edit: current };
  }

  const addedLength = current.text.length - previous.text.length;
  const insertAt = previous.selectionStart;
  const addedText = current.text.substring(insertAt, insertAt + addedLength);

  if (addedText === ' ') {
    const lineStart = current.text.lastIndexOf('\n', insertAt - 1);
    const lineStartIndex = lineStart === -1 ? 0 : lineStart + 1;
    const linePrefix = current.text.substring(lineStartIndex, insertAt + 1);

    if (linePrefix === '* ' || linePrefix === '- ') {
      const newText = replaceRange(current.text, lineStartIndex, insertAt + 1, '• ');
      return {
        edit: { text: newText, selectionStart: lineStartIndex + 2, selectionEnd: lineStartIndex + 2 },
      };
    }

    if (linePrefix === '[] ' || linePrefix === '[ ] ') {
      return { edit: current, structureChanged: true };
    }
  }

  if (addedText === '\n') {
    const lineBreakAt = insertAt;
    const lastLineStart = current.text.lastIndexOf('\n', lineBreakAt - 1);
    const lastLineStartIndex = lastLineStart === -1 ? 0 : lastLineStart + 1;
    const lastLine = current.text.substring(lastLineStartIndex, lineBreakAt);

    if (lastLine.startsWith('• ')) {
      if (lastLine.trim() === '•') {
        const newText = replaceRange(current.text, lastLineStartIndex, lineBreakAt + 1, '\n');
        return {
          edit: {
            text: newText,
            selectionStart: lastLineStartIndex + 1,
            selectionEnd: lastLineStartIndex + 1,
          },
        };
      }

      const newText =
        current.text.substring(0, lineBreakAt + 1) +
        '• ' +
        current.text.substring(lineBreakAt + 1);
      return {
        edit: {
          text: newText,
          selectionStart: lineBreakAt + 3,
          selectionEnd: lineBreakAt + 3,
        },
      };
    }
  }

  return { edit: current };
}
