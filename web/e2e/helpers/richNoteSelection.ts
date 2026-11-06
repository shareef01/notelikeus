import type { Locator } from '@playwright/test';

export async function selectNoteText(body: Locator, start: number, end: number) {
  await body.focus();
  await body.evaluate((field, offsets) => {
    const walker = document.createTreeWalker(field, NodeFilter.SHOW_TEXT);
    const range = document.createRange();
    let position = 0;
    let begun = false;
    while (walker.nextNode()) {
      const node = walker.currentNode;
      const length = node.textContent?.length ?? 0;
      if (!begun && offsets.start <= position + length) {
        range.setStart(node, offsets.start - position); begun = true;
      }
      if (offsets.end <= position + length) {
        range.setEnd(node, offsets.end - position); break;
      }
      position += length;
    }
    const selection = window.getSelection()!;
    selection.removeAllRanges(); selection.addRange(range);
    document.dispatchEvent(new Event('selectionchange'));
  }, { start, end });
}

