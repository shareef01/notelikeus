/** Strip lightweight markdown for note card previews (matches Android RichTextParser). */
export function stripMarkdownForPreview(text: string): string {
  return text
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/_(.+?)_/g, '$1')
    .replace(/\[(.+?)\]\([^)]+\)/g, '$1')
    .replace(/^• /gm, '');
}

export function wrapSelection(
  text: string,
  selectionStart: number,
  selectionEnd: number,
  marker: string,
): { text: string; selectionStart: number; selectionEnd: number } {
  const start = Math.min(selectionStart, selectionEnd);
  const end = Math.max(selectionStart, selectionEnd);

  if (start === end) {
    const wrapped = `${marker}${marker}`;
    const nextText = text.slice(0, start) + wrapped + text.slice(end);
    const cursor = start + marker.length;
    return { text: nextText, selectionStart: cursor, selectionEnd: cursor };
  }

  const selected = text.slice(start, end);
  const wrapped = `${marker}${selected}${marker}`;
  const nextText = text.slice(0, start) + wrapped + text.slice(end);
  const cursorStart = start + marker.length;
  const cursorEnd = cursorStart + selected.length;
  return { text: nextText, selectionStart: cursorStart, selectionEnd: cursorEnd };
}

export function prefixLinesWithBullet(
  text: string,
  selectionStart: number,
  selectionEnd: number,
): { text: string; selectionStart: number; selectionEnd: number } {
  const start = Math.min(selectionStart, selectionEnd);
  const end = Math.max(selectionStart, selectionEnd);
  const lineStart = text.lastIndexOf('\n', start - 1);
  const blockStart = lineStart === -1 ? 0 : lineStart + 1;
  const lineEnd = text.indexOf('\n', end);
  const blockEnd = lineEnd === -1 ? text.length : lineEnd;
  const block = text.slice(blockStart, blockEnd);
  const prefixed = block
    .split('\n')
    .map((line) => (line.trim() === '' || line.startsWith('• ') ? line : `• ${line}`))
    .join('\n');
  const nextText = text.slice(0, blockStart) + prefixed + text.slice(blockEnd);
  return {
    text: nextText,
    selectionStart: blockStart,
    selectionEnd: blockStart + prefixed.length,
  };
}

const SAFE_HREF_SCHEME = /^(?:https?:|mailto:)/i;
const ANY_SCHEME = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;

/**
 * Normalizes a note-authored URL for use as an anchor href. Bare domains/paths
 * are treated as https (matching the link-insert dialog); any explicit scheme
 * other than http(s)/mailto (javascript:, data:, vbscript:, ...) is rejected,
 * since note content can arrive unreviewed via typed text or backup import.
 */
export function toSafeHref(url: string): string {
  const trimmed = url.trim();
  if (SAFE_HREF_SCHEME.test(trimmed)) return trimmed;
  if (!ANY_SCHEME.test(trimmed)) return `https://${trimmed}`;
  return '#';
}

export function wrapSelectionAsLink(
  text: string,
  selectionStart: number,
  selectionEnd: number,
  url: string,
): { text: string; selectionStart: number; selectionEnd: number } | null {
  const trimmedUrl = url.trim();
  if (!trimmedUrl) return null;

  const start = Math.min(selectionStart, selectionEnd);
  const end = Math.max(selectionStart, selectionEnd);
  const selected = start === end ? 'link' : text.slice(start, end);
  const normalizedUrl = toSafeHref(trimmedUrl);
  const link = `[${selected}](${normalizedUrl})`;
  const nextText = text.slice(0, start) + link + text.slice(end);
  if (start === end) {
    // Select the placeholder label so the user can type over it.
    return {
      text: nextText,
      selectionStart: start + 1,
      selectionEnd: start + 1 + selected.length,
    };
  }
  return { text: nextText, selectionStart: start + link.length, selectionEnd: start + link.length };
}
