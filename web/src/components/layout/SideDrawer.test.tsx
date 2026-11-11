import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';

const isTabletUp = vi.hoisted(() => ({ value: false }));

vi.mock('@/hooks/useMediaQuery', () => ({
  useIsTabletUp: () => isTabletUp.value,
  useIsWide: () => false,
  useMediaQuery: () => false,
}));

import { SideDrawer } from '@/components/layout/SideDrawer';
import { narrowingOf } from '@/lib/query/noteQuery';

function render(
  open: boolean,
  tablet: boolean,
  options: { collapsed?: boolean; onSignIn?: () => void } = {},
) {
  isTabletUp.value = tablet;
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(
      createElement(SideDrawer, {
        open,
        collapsed: options.collapsed ?? false,
        currentFilter: 'active',
        onClose: () => {},
        onToggleCollapse: () => {},
        onNavigate: () => {},
        userEmail: null,
        onSignIn: options.onSignIn ?? (() => {}),
        onSignOut: () => {},
      }),
    );
  });
  const aside = container.querySelector('aside') as HTMLElement;
  return {
    aside,
    cleanup: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

/**
 * Below the tablet breakpoint the drawer is a modal overlay; at and above it, it is the page's
 * static sidebar. A closed mobile drawer is only translated off-screen, so without `inert` its
 * links stayed keyboard-reachable while marked `aria-hidden` — focusable content inside a hidden
 * subtree, and navigation the user tabs through without being able to see it.
 */
describe('SideDrawer', () => {
  it('takes a closed mobile drawer out of the tab order', () => {
    const { aside, cleanup } = render(false, false);

    expect(aside.hasAttribute('inert')).toBe(true);
    expect(aside.getAttribute('role')).toBeNull();

    cleanup();
  });

  it('marks an open mobile drawer as a modal dialog', () => {
    const { aside, cleanup } = render(true, false);

    expect(aside.hasAttribute('inert')).toBe(false);
    expect(aside.getAttribute('role')).toBe('dialog');
    expect(aside.getAttribute('aria-modal')).toBe('true');

    cleanup();
  });

  it('never makes the static tablet sidebar inert or modal', () => {
    const { aside, cleanup } = render(false, true);

    expect(aside.hasAttribute('inert')).toBe(false);
    expect(aside.getAttribute('role')).toBeNull();
    expect(aside.getAttribute('aria-modal')).toBeNull();

    cleanup();
  });

  it('locks page scrolling only while the mobile overlay is open', () => {
    const closed = render(false, false);
    expect(document.body.style.overflow).not.toBe('hidden');
    closed.cleanup();

    const open = render(true, false);
    expect(document.body.style.overflow).toBe('hidden');
    open.cleanup();

    expect(document.body.style.overflow).not.toBe('hidden');
  });

  it('offers a plain Sign in button that opens the auth screen when signed out', () => {
    const onSignIn = vi.fn();
    const { aside, cleanup } = render(true, false, { onSignIn });

    const buttons = Array.from(aside.querySelectorAll('button')).filter(
      (button) => button.textContent?.trim() === 'Sign in',
    );
    expect(buttons).toHaveLength(1);
    act(() => buttons[0]!.click());
    expect(onSignIn).toHaveBeenCalledTimes(1);
    expect(aside.textContent?.toLowerCase()).not.toContain('google');

    cleanup();
  });

  it('keeps an accessible Sign in button on the collapsed tablet sidebar', () => {
    const onSignIn = vi.fn();
    const { aside, cleanup } = render(false, true, { collapsed: true, onSignIn });

    const button = aside.querySelector('button[aria-label="Sign in"]') as HTMLButtonElement | null;
    expect(button).not.toBeNull();
    act(() => button!.click());
    expect(onSignIn).toHaveBeenCalledTimes(1);

    cleanup();
  });
});

/** NL-04: native's drawer has Reminders, Unfinished and Unlabelled views; web had none. */
describe('SideDrawer smart views', () => {
  it('lists the views with counts, marks the one on screen, and reports a choice', () => {
    isTabletUp.value = true;
    const chosen: string[] = [];
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    act(() => {
      root.render(
        createElement(SideDrawer, {
          open: true,
          collapsed: false,
          currentFilter: 'active',
          onClose: () => {},
          onToggleCollapse: () => {},
          onNavigate: () => {},
          userEmail: null,
          onSignIn: () => {},
          onSignOut: () => {},
          currentSmartView: 'reminders',
          onSmartView: (id) => chosen.push(id),
          smartViewCounts: { reminders: 2, unfinished: 0, unlabeled: 5 },
        }),
      );
    });

    const views = container.querySelector('section[aria-label="Views"]') as HTMLElement;
    const buttons = [...views.querySelectorAll('button')];
    expect(buttons.map((b) => b.textContent)).toEqual(['Reminders2', 'Unfinished', 'Unlabelled5']);
    expect(buttons[0]!.getAttribute('aria-current')).toBe('page');
    // The Library row the view lives under is not also the current page.
    const notes = [...container.querySelectorAll('button')].find((b) => b.textContent?.startsWith('Notes'));
    expect(notes?.getAttribute('aria-current')).toBeNull();

    act(() => buttons[1]!.click());
    expect(chosen).toEqual(['unfinished']);

    act(() => root.unmount());
    container.remove();
  });
});

describe('SideDrawer saved filters', () => {
  function renderWithSaved(props: Partial<Parameters<typeof SideDrawer>[0]>) {
    isTabletUp.value = false;
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    act(() => {
      root.render(
        createElement(SideDrawer, {
          open: true,
          collapsed: false,
          currentFilter: 'active',
          onClose: () => {},
          onToggleCollapse: () => {},
          onNavigate: () => {},
          userEmail: null,
          onSignIn: () => {},
          onSignOut: () => {},
          onSmartView: () => {},
          ...props,
        }),
      );
    });
    return {
      container,
      cleanup: () => {
        act(() => root.unmount());
        container.remove();
      },
    };
  }

  const saved = [
    { name: 'Invoices', query: { ...narrowingOf({ filter: 'active' }), labelNames: ['Money'] } },
    { name: 'Green', query: narrowingOf({ filter: 'active', colors: [1] }) },
  ];

  it('has no Saved section while nothing is saved', () => {
    const { container, cleanup } = renderWithSaved({ savedFilters: [], onApplySavedFilter: () => {} });
    expect(container.querySelector('section[aria-label="Saved"]')).toBeNull();
    cleanup();
  });

  it('lists saved filters after Views, marks the current one, applies and deletes', () => {
    const onApplySavedFilter = vi.fn();
    const onDeleteSavedFilter = vi.fn();
    const onClose = vi.fn();
    const { container, cleanup } = renderWithSaved({
      savedFilters: saved,
      currentSavedFilter: 'Green',
      onApplySavedFilter,
      onDeleteSavedFilter,
      onClose,
    });
    const sections = Array.from(container.querySelectorAll('section')).map((s) => s.getAttribute('aria-label'));
    expect(sections.indexOf('Saved')).toBe(sections.indexOf('Views') + 1);

    const section = container.querySelector('section[aria-label="Saved"]')!;
    const rows = Array.from(section.querySelectorAll('li > div > button'));
    expect(rows.map((b) => b.textContent)).toEqual(['Invoices', 'Green']);
    expect(rows[1]!.getAttribute('aria-current')).toBe('page');
    // The Library row is not the current page while a saved filter is.
    const notesRow = Array.from(container.querySelectorAll('button')).find((b) => b.textContent?.startsWith('Notes'));
    expect(notesRow?.getAttribute('aria-current')).toBeNull();

    act(() => (rows[0] as HTMLButtonElement).click());
    expect(onApplySavedFilter).toHaveBeenCalledWith(saved[0]);
    expect(onClose).toHaveBeenCalled();

    const remove = section.querySelector<HTMLButtonElement>('button[aria-label="Delete saved filter Invoices"]')!;
    act(() => remove.click());
    expect(onDeleteSavedFilter).toHaveBeenCalledWith('Invoices');
    cleanup();
  });
});
