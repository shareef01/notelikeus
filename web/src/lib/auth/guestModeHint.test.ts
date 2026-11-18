import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GUEST_MODE_STORAGE_KEY, SESSION_HINT_STORAGE_KEY } from '@/lib/auth/sessionHint';

/** The store reads the hint once, when its module loads — as it does on a page load. */
async function freshAuthStore() {
  vi.resetModules();
  return (await import('@/store/authStore')).useAuthStore;
}

describe('guest mode across reloads', () => {
  beforeEach(() => localStorage.clear());

  it('a first visit starts at the sign-in gate', async () => {
    expect((await freshAuthStore()).getState().guestMode).toBe(false);
  });

  it('a guest who reloads is still a guest', async () => {
    (await freshAuthStore()).getState().enterGuestMode();
    expect((await freshAuthStore()).getState().guestMode).toBe(true);
  });

  it('leaving guest mode, by signing in or a reset, is remembered too', async () => {
    const store = await freshAuthStore();
    store.getState().enterGuestMode();
    store.getState().exitGuestMode();
    expect((await freshAuthStore()).getState().guestMode).toBe(false);

    const again = await freshAuthStore();
    again.getState().enterGuestMode();
    again.getState().reset();
    expect(localStorage.getItem(GUEST_MODE_STORAGE_KEY)).toBeNull();
    expect((await freshAuthStore()).getState().guestMode).toBe(false);
  });

  it('a signed-in session hint wins over a stale guest flag', async () => {
    localStorage.setItem(GUEST_MODE_STORAGE_KEY, '1');
    localStorage.setItem(SESSION_HINT_STORAGE_KEY, '1');
    expect((await freshAuthStore()).getState().guestMode).toBe(false);
  });
});
