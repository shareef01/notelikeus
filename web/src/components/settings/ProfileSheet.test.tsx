import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement, type ComponentProps } from 'react';
import { createRoot, type Root } from 'react-dom/client';

import { ProfileSheet } from '@/components/settings/ProfileSheet';

type Props = ComponentProps<typeof ProfileSheet>;

/**
 * Settings opens with the account, shows the view and sort options instead of cycling through
 * them, and keeps Sign out and Delete account last.
 */
describe('ProfileSheet layout', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  async function render(overrides: Partial<Props> = {}) {
    const props: Props = {
      open: true,
      onClose: () => {},
      noteCount: 3,
      viewColumns: 2,
      sortOrder: 'newest',
      onViewColumnsSelect: vi.fn(),
      onSortOrderSelect: vi.fn(),
      theme: { base: 'system', accent: 'neutral', amoled: false } as Props['theme'],
      onThemeBaseChange: () => {},
      onAccentChange: () => {},
      onAmoledChange: () => {},
      isSignedIn: false,
      isGuest: true,
      userEmail: null,
      syncStatus: 'offline',
      onExportBackup: () => {},
      onExportCompleteBackup: () => {},
      onExportDeviceBackup: () => {},
      onShowDiagnostics: () => {},
      onImportBackup: () => {},
      onPrivacyPolicy: () => {},
      onSignIn: () => {},
      onSignUp: () => {},
      onSignOut: () => {},
      onDeleteAccount: () => {},
      ...overrides,
    };
    await act(async () => {
      root.render(createElement(ProfileSheet, props));
    });
    return props;
  }

  const headings = () => [...document.querySelectorAll('h3')].map((h) => h.textContent);
  const chip = (group: string, label: string) =>
    [...document.querySelectorAll(`[role="group"][aria-label="${group}"] button`)].find((b) => b.textContent === label) as HTMLButtonElement;

  it('puts the status and note count on the account row, with no Insights section', async () => {
    await render();

    expect(document.body.textContent).toContain('Browsing as a guestNotes stay in this browser · 3 notes');
    expect(headings()).toEqual(['Account & Sync', 'Appearance', 'Layout', 'Data & Backup', 'About']);
  });

  it('shows Sign out and Delete account last, and only when signed in', async () => {
    await render({ isSignedIn: true, isGuest: false, userEmail: 'a@example.com', syncStatus: 'online' });

    expect(document.body.textContent).toContain('a@example.comConnected · 3 notes');
    expect(headings().at(-1)).toBe('Account');
    expect(document.body.textContent).not.toContain('Create account');
  });

  it('chooses the view and sort directly', async () => {
    const props = await render();

    expect(chip('Default view', 'Grid').getAttribute('aria-pressed')).toBe('true');
    await act(async () => chip('Default view', 'List').click());
    await act(async () => chip('Sort order', 'Oldest first').click());

    expect(props.onViewColumnsSelect).toHaveBeenCalledWith(1);
    expect(props.onSortOrderSelect).toHaveBeenCalledWith('oldest');
  });
});
