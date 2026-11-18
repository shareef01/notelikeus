import { describe, expect, it } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { FilterRow } from '@/components/layout/FilterRow';
import { labelFromName } from '@/types/label';

function render(
  selectedLabelName: string | null,
  colors: { usedColors?: readonly number[]; selectedColors?: readonly number[] } = {},
) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(
      createElement(FilterRow, {
        sortOrder: 'manual',
        onSortOrderClick: () => {},
        selectedColors: colors.selectedColors ?? [],
        onColorToggle: () => {},
        onColorsClear: () => {},
        usedColors: colors.usedColors,
        labels: [labelFromName('Work')],
        selectedLabelNames: selectedLabelName ? [selectedLabelName] : [],
        onLabelToggle: () => {},
        onLabelsClear: () => {},
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

const swatchNames = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('[aria-label="Color filter"] button')).map((button) =>
    button.getAttribute('aria-label'),
  );

describe('FilterRow', () => {
  it('offers no colour control while every note is uncoloured', () => {
    const { container, cleanup } = render(null, { usedColors: [] });
    expect(container.querySelector('[aria-label="Color filter"]')).toBeNull();
    cleanup();
  });

  it('offers only the colours notes use, under their palette names, in either theme', () => {
    // Light red and dark blue: a note keeps the colour it was given in the other theme.
    const { container, cleanup } = render(null, { usedColors: [0xffffcdd2 | 0, 0xff2a4a6e | 0] });
    expect(swatchNames(container)).toEqual(['All colors', 'Red', 'Blue']);
    cleanup();
  });

  it('keeps a selected colour on offer after its last note loses it', () => {
    const { container, cleanup } = render(null, { usedColors: [], selectedColors: [0xffc8e6c9 | 0] });
    expect(swatchNames(container)).toEqual(['All colors', 'Green']);
    cleanup();
  });

  it('offers the whole palette when the caller does not say which colours are used', () => {
    const { container, cleanup } = render(null);
    expect(swatchNames(container)).toHaveLength(9);
    cleanup();
  });

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
          onSortOrderClick: () => {},
          sortDisabled: true,
          selectedColors: [],
          onColorToggle: () => {},
          onColorsClear: () => {},
          labels: [],
          selectedLabelNames: [],
          onLabelToggle: () => {},
          onLabelsClear: () => {},
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
