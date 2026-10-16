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
