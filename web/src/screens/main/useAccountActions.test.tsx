import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
const auth = vi.hoisted(() => ({ deleteAccount: vi.fn(), signOutAccount: vi.fn() }));
vi.mock('@/lib/auth/accountAuth', () => auth);
import { useAccountActions } from '@/screens/main/useAccountActions';
import { ReauthenticationRequiredError } from '@/lib/auth/reauthentication';
import { useAuthStore } from '@/store/authStore';

let root: Root;
let container: HTMLDivElement;
let actions: ReturnType<typeof useAccountActions>;
function Harness({ owner }: { owner: string }) {
  actions = useAccountActions({ notes: [], userId: owner, closeSignOutConfirm: () => {}, closeDeleteAccountConfirm: () => {}, closeProfile: () => {} });
  return null;
}
async function render(owner: string) {
  useAuthStore.getState().setUser({ uid: owner, email: null, displayName: null });
  await act(async () => { root.render(createElement(Harness, { owner })); });
}
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.resetAllMocks();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); container.remove(); });

it('hides an old account identity prompt and refuses to retry its deletion under another account', async () => {
  auth.deleteAccount.mockRejectedValueOnce(new ReauthenticationRequiredError());
  await render('owner-a');
  await act(async () => { await actions.deleteAccount(); });
  expect(auth.deleteAccount).toHaveBeenCalledWith('owner-a');
  expect(actions.identityCheck).toBe('deleteAccount');
  await render('owner-b');
  expect(actions.identityCheck).toBeNull();
  await act(async () => { await actions.confirmIdentity(); });
  expect(auth.deleteAccount).toHaveBeenCalledOnce();
});

it('retries the authorized owner after that same account confirms its identity', async () => {
  auth.deleteAccount.mockRejectedValueOnce(new ReauthenticationRequiredError()).mockResolvedValueOnce(undefined);
  await render('owner-a');
  await act(async () => { await actions.deleteAccount(); });
  await act(async () => { await actions.confirmIdentity(); });
  expect(auth.deleteAccount.mock.calls).toEqual([['owner-a'], ['owner-a']]);
  expect(actions.identityCheck).toBeNull();
});
