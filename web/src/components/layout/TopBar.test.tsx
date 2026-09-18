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
      onSortOrderCycle: vi.fn(),
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
});
