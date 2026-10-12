import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';

const countPendingPushes = vi.fn();

vi.mock('@/lib/local/notesLocalRepository', () => ({
  countPendingPushes: (...args: unknown[]) => countPendingPushes(...args),
}));

import { SignOutDialog } from '@/components/settings/SignOutDialog';
import { useAuthStore } from '@/store/authStore';

/** FU-1: signing out must say when this browser holds edits the cloud has not received. */
describe('SignOutDialog unsynced-edit notice', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    countPendingPushes.mockReset();
    useAuthStore.getState().setUser({ uid: 'user-a', email: null, displayName: null });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
    useAuthStore.getState().setUser(null);
  });

  async function renderOpen() {
    await act(async () => {
      root.render(
        createElement(SignOutDialog, {
          open: true,
          onCancel: () => {},
          onSignOut: () => {},
          onSignOutAndDelete: () => {},
        }),
      );
    });
  }

  it('says how many notes have edits not uploaded yet, and that they are kept', async () => {
    countPendingPushes.mockResolvedValue(2);

    await renderOpen();

    expect(countPendingPushes).toHaveBeenCalledWith('user-a');
    const text = document.body.textContent ?? '';
    expect(text).toContain('2 notes have edits that haven’t been uploaded yet.');
    expect(text).toContain('uploads the next time you sign in to this account here');
  });

  it('says nothing extra when everything is uploaded', async () => {
    countPendingPushes.mockResolvedValue(0);

    await renderOpen();

    expect(document.body.textContent ?? '').not.toContain('uploaded yet');
  });
});
