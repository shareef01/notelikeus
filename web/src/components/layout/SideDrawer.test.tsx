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

function render(open: boolean, tablet: boolean) {
  isTabletUp.value = tablet;
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(
      createElement(SideDrawer, {
        open,
        collapsed: false,
        currentFilter: 'active',
        onClose: () => {},
        onToggleCollapse: () => {},
        onNavigate: () => {},
        userEmail: null,
        onSignIn: () => {},
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
});
