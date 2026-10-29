import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { TopBar } from '@/components/layout/TopBar';
import { labelFromName } from '@/types/label';

describe('TopBar - UX-E Search Header Stability', () => {
  function renderTopBar(props: Partial<Parameters<typeof TopBar>[0]> = {}) {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    const defaultProps: Parameters<typeof TopBar>[0] = {
      searchQuery: '',
      onSearchQueryChange: vi.fn(),
      currentFilter: 'active',
      listScrolled: false,
      sortOrder: 'manual',
      onSortOrderClick: vi.fn(),
      selectedColor: null,
      onColorSelect: vi.fn(),
      labels: [labelFromName('Work'), labelFromName('Personal')],
      selectedLabelName: 'Work',
      onLabelSelect: vi.fn(),
      hasActiveFilters: true,
      onClearFilters: vi.fn(),
      onMenuClick: vi.fn(),
      onProfileClick: vi.fn(),
      viewColumns: 2,
      onViewColumnsChange: vi.fn(),
      recentSearches: ['groceries', 'project roadmap'],
      onRecentSearchClick: vi.fn(),
      onClearRecentSearches: vi.fn(),
      ...props,
    };

    act(() => {
      root.render(createElement(TopBar, defaultProps));
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

  it('keeps FilterRow mounted when search input receives focus', () => {
    const { container, cleanup } = renderTopBar();

    // Verify label filters are mounted initially
    const workChipBefore = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent === 'Work',
    );
    expect(workChipBefore).toBeTruthy();

    const searchInput = container.querySelector('input[type="search"]');
    expect(searchInput).toBeTruthy();

    // Focus search input
    act(() => {
      (searchInput as HTMLInputElement).focus();
    });

    // UX-E requirement: FilterRow should NOT be unmounted when search is focused
    const workChipAfter = Array.from(container.querySelectorAll('button')).find(
      (b) => b.textContent === 'Work',
    );
    expect(workChipAfter).toBeTruthy();

    // Recent searches overlay should be present
    const recentHeading = Array.from(container.querySelectorAll('*')).find(
      (el) => el.textContent?.includes('Recent searches') || el.textContent === 'Recent',
    );
    expect(recentHeading).toBeTruthy();

    cleanup();
  });

  const recentPanel = (container: HTMLElement) =>
    container.querySelector('[aria-label="Recent searches"]');

  const recentChip = (container: HTMLElement, name: string) =>
    Array.from(recentPanel(container)?.querySelectorAll('button') ?? []).find(
      (b) => b.textContent === name,
    ) as HTMLButtonElement | undefined;

  it('keeps the recent searches open while Tab moves focus into them', async () => {
    const { container, cleanup } = renderTopBar();
    const input = container.querySelector('input[type="search"]') as HTMLInputElement;

    act(() => input.focus());
    const chip = recentChip(container, 'groceries');
    expect(chip).toBeTruthy();

    // What Tab does: the field blurs and the chip takes focus in the same task.
    act(() => chip!.focus());
    // The old field-only blur handler closed the panel 200 ms later.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 260));
    });

    expect(recentPanel(container)).toBeTruthy();
    expect(document.activeElement).toBe(recentChip(container, 'groceries'));
    cleanup();
  });

  it('runs a recent search chosen from the keyboard', async () => {
    const onRecentSearchClick = vi.fn();
    const { container, cleanup } = renderTopBar({ onRecentSearchClick });
    const input = container.querySelector('input[type="search"]') as HTMLInputElement;

    act(() => input.focus());
    act(() => recentChip(container, 'project roadmap')!.focus());
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 260));
    });
    act(() => recentChip(container, 'project roadmap')!.click());

    expect(onRecentSearchClick).toHaveBeenCalledWith('project roadmap');
    cleanup();
  });

  it('closes the recent searches when focus leaves the search form', () => {
    const { container, cleanup } = renderTopBar();
    const input = container.querySelector('input[type="search"]') as HTMLInputElement;
    const outside = document.createElement('button');
    document.body.appendChild(outside);

    act(() => input.focus());
    expect(recentPanel(container)).toBeTruthy();
    act(() => outside.focus());
    expect(recentPanel(container)).toBeNull();

    outside.remove();
    cleanup();
  });

  it('closes the recent searches on Escape and keeps focus in the field', () => {
    const { container, cleanup } = renderTopBar();
    const input = container.querySelector('input[type="search"]') as HTMLInputElement;

    act(() => input.focus());
    act(() => recentChip(container, 'groceries')!.focus());
    act(() => {
      document.activeElement!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
      );
    });

    expect(recentPanel(container)).toBeNull();
    expect(document.activeElement).toBe(input);
    cleanup();
  });

  it('keeps focus in the field after clearing the history', () => {
    const { container, cleanup } = renderTopBar();
    const input = container.querySelector('input[type="search"]') as HTMLInputElement;

    act(() => input.focus());
    const clear = Array.from(recentPanel(container)!.querySelectorAll('button')).find(
      (b) => b.textContent === 'Clear',
    ) as HTMLButtonElement;
    act(() => clear.focus());
    act(() => clear.click());

    expect(document.activeElement).toBe(input);
    cleanup();
  });

  it('leaves out the sort and filter row when the view has nothing to filter', () => {
    const { container, cleanup } = renderTopBar({ showFilterRow: false });
    expect(container.querySelector('[role="group"][aria-label="Filters"]')).toBeNull();
    expect(container.querySelector('input[type="search"]')).toBeTruthy();
    cleanup();
  });
});
