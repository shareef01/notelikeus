import React from 'react';
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { describe, expect, it, vi } from 'vitest';

const mockState = {
  user: null as { uid: string; email: string | null } | null,
  isGuest: false,
  online: true,
};

vi.mock('@/hooks/useAuth', () => ({
  useAuthListener: () => ({
    userId: mockState.user?.uid ?? null,
    user: mockState.user,
    isGuest: mockState.isGuest,
  }),
}));

vi.mock('@/hooks/useOnlineStatus', () => ({
  useOnlineStatus: () => mockState.online,
}));

import { useCloudSync } from '@/hooks/useCloudSync';

function testHook<T>(hook: () => T): T {
  let result!: T;
  function TestComponent() {
    result = hook();
    return null;
  }
  const div = document.createElement('div');
  const root = createRoot(div);
  flushSync(() => {
    root.render(React.createElement(TestComponent));
  });
  root.unmount();
  return result;
}

describe('useCloudSync', () => {
  it('reports truthful online connectivity and never claims full sync or exposes fake syncedCount', () => {
    mockState.user = { uid: 'user-1', email: 'test@example.com' };
    mockState.online = true;

    const result = testHook(() => useCloudSync());

    expect(result.online).toBe(true);
    // Truthful status: 'online' (network reachability), NOT falsely claiming 'synced'
    expect(result.status).toBe('online');
    expect(result.status).not.toBe('synced');
    // Must NOT have misleading syncedCount
    expect((result as Record<string, unknown>).syncedCount).toBeUndefined();
  });

  it('reports offline status when network is disconnected', () => {
    mockState.user = { uid: 'user-1', email: 'test@example.com' };
    mockState.online = false;

    const result = testHook(() => useCloudSync());

    expect(result.online).toBe(false);
    expect(result.status).toBe('offline');
  });

  it('reports unknown status when user is not signed in', () => {
    mockState.user = null;
    mockState.online = true;

    const result = testHook(() => useCloudSync());

    expect(result.status).toBe('unknown');
  });
});
