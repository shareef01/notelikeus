import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';

let online = true;
vi.mock('@/hooks/useOnlineStatus', () => ({ useOnlineStatus: () => online }));
const retryNotesLoad = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/notes/retryNotesLoad', () => ({ retryNotesLoad: () => retryNotesLoad() }));

import { SyncStatusBanner } from '@/components/layout/SyncStatusBanner';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useSyncStatusStore } from '@/store/syncStatusStore';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const USER = 'user-1';

function render() {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => root.render(createElement(SyncStatusBanner)));
  return { container, unmount: () => act(() => root.unmount()) };
}

function queue(ids: string[], { failed = false, flushing = false } = {}) {
  const store = useSyncStatusStore.getState();
  store.setPending(USER, ids);
  store.setFailed(USER, failed);
  store.setFlushing(USER, flushing);
}

describe('SyncStatusBanner', () => {
  beforeEach(() => {
    online = true;
    retryNotesLoad.mockClear();
    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });
    useNotesStore.getState().clearSyncError();
    queue([]);
  });

  afterEach(() => {
    useAuthStore.getState().setUser(null);
    document.body.innerHTML = '';
  });

  it('shows nothing when online and everything has reached the cloud', () => {
    const { container, unmount } = render();
    expect(container.textContent).toBe('');
    // The live region is already there, so the message is announced when it arrives.
    expect(container.querySelector('[role="status"]')).not.toBeNull();
    unmount();
  });

  it('counts the edits waiting while offline', () => {
    online = false;
    queue(['1', '2']);
    const { container, unmount } = render();
    expect(container.textContent).toContain('2 edits saved on this device will sync when you reconnect');
    unmount();
  });

  it('tells a guest only that the notes are here, with no promise of syncing', () => {
    online = false;
    useAuthStore.getState().setUser(null);
    const { container, unmount } = render();
    expect(container.textContent).toContain('Your notes are on this device');
    expect(container.textContent).not.toMatch(/sync/i);
    unmount();
  });

  it('says when a push left an edit behind, and Retry runs the sync again', () => {
    queue(['1'], { failed: true });
    const { container, unmount } = render();
    expect(container.textContent).toContain('1 edit couldn’t sync yet');
    const retry = [...container.querySelectorAll('button')].find((b) => b.textContent === 'Retry');
    act(() => retry!.click());
    expect(retryNotesLoad).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('stays quiet while the retry is running, and when the sync warning already offers one', () => {
    queue(['1'], { failed: true, flushing: true });
    const flushing = render();
    expect(flushing.container.textContent).toBe('');
    flushing.unmount();

    queue(['1'], { failed: true });
    useNotesStore.getState().setSyncError('Could not sync notes.');
    const warned = render();
    expect(warned.container.textContent).toBe('');
    warned.unmount();
  });
});
