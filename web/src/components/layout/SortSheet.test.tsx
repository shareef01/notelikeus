import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  REORDER_FILTERED_HINT,
  SORT_OPTION_LABELS,
  SortSheet,
} from '@/components/layout/SortSheet';

describe('SortSheet', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    act(() => {
      root.render(
        createElement(SortSheet, {
          open: true,
          onClose: () => {},
          sortOrder: 'newest',
          onSelect: () => {},
        }),
      );
    });
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  it('gives every order a hint that adds something to its label', () => {
    const options = Array.from(document.querySelectorAll('button[aria-pressed]'));
    expect(options).toHaveLength(3);
    for (const option of options) {
      const [label, hint] = Array.from(option.querySelectorAll('span > span')).map(
        (span) => span.textContent ?? '',
      );
      expect(Object.values(SORT_OPTION_LABELS)).toContain(label);
      expect(hint).toBeTruthy();
      // "Oldest first" was once hinted as "Oldest first".
      expect(hint.toLowerCase()).not.toBe(label.toLowerCase());
    }
  });

  it('offers Manual first, as native does (NL-05)', () => {
    const labels = Array.from(document.querySelectorAll('button[aria-pressed]')).map(
      (option) => option.querySelector('span > span')?.textContent,
    );
    expect(labels).toEqual(['Manual', 'Newest first', 'Oldest first']);
    expect(document.body.textContent).not.toContain(REORDER_FILTERED_HINT);
  });

  it('says under Manual when a filter is what blocks moving notes', () => {
    act(() => {
      root.render(
        createElement(SortSheet, {
          open: true,
          onClose: () => {},
          sortOrder: 'manual',
          onSelect: () => {},
          reorderBlockedByFilters: true,
        }),
      );
    });
    const manual = document.querySelector('button[aria-pressed="true"]');
    expect(manual?.textContent).toContain(REORDER_FILTERED_HINT);
  });
});
