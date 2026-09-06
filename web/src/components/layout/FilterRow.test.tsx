import { describe, expect, it } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { FilterRow } from '@/components/layout/FilterRow';
import { labelFromName } from '@/types/label';

function render(selectedLabelName: string | null) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(
      createElement(FilterRow, {
        sortOrder: 'manual',
        onSortOrderCycle: () => {},
        selectedColor: null,
        onColorSelect: () => {},
        labels: [labelFromName('Work')],
        selectedLabelName,
        onLabelSelect: () => {},
        hasActiveFilters: false,
        onClearFilters: () => {},
      }),
    );
  });
  return {
    container,
    cleanup: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

describe('FilterRow', () => {
  it('exposes aria-pressed on label filter chips', () => {
    const { container, cleanup } = render('Work');
    const work = Array.from(container.querySelectorAll('button')).find(
      (button) => button.textContent === 'Work',
    );
    const all = Array.from(container.querySelectorAll('button')).find(
      (button) => button.textContent === 'All labels',
    );
    expect(work?.getAttribute('aria-pressed')).toBe('true');
    expect(all?.getAttribute('aria-pressed')).toBe('false');
    cleanup();
  });

  it('does not mark the sort-cycle chip as pressed', () => {
    const { container, cleanup } = render(null);
    const sort = Array.from(container.querySelectorAll('button')).find(
      (button) => button.textContent?.includes('Manual'),
    );
    expect(sort?.hasAttribute('aria-pressed')).toBe(false);
    cleanup();
  });
});
