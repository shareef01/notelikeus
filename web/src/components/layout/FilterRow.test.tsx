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

  it('marks Relevance sort chip as active/selected when sort is disabled during search', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    act(() => {
      root.render(
        createElement(FilterRow, {
          sortOrder: 'manual',
          onSortOrderCycle: () => {},
          sortDisabled: true,
          selectedColor: null,
          onColorSelect: () => {},
          labels: [],
          selectedLabelName: null,
          onLabelSelect: () => {},
          hasActiveFilters: false,
          onClearFilters: () => {},
        }),
      );
    });

    const relevanceChip = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Relevance'),
    );
    expect(relevanceChip).toBeTruthy();
    expect(relevanceChip?.classList.contains('filter-chip-active')).toBe(true);

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('keeps label chips in a single-row scrollable container without md:flex-wrap', () => {
    const { container, cleanup } = render('Work');
    const labelContainer = Array.from(container.querySelectorAll('div')).find((d) =>
      d.classList.contains('overflow-x-auto') && d.textContent?.includes('All labels'),
    );
    expect(labelContainer).toBeTruthy();
    expect(labelContainer?.className).not.toContain('md:flex-wrap');
    cleanup();
  });
});
