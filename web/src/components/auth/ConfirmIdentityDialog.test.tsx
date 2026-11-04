import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';

const { auth } = vi.hoisted(() => ({
  auth: {
    sendEmailSignInCode: vi.fn(async () => {}),
    verifyEmailSignInCode: vi.fn(async () => {}),
  },
}));

vi.mock('@/lib/auth/accountAuth', () => auth);
vi.mock('@/hooks/useAuth', () => ({ ensureSupabaseAuthStarted: vi.fn(async () => {}) }));
vi.mock('@/lib/auth/turnstile', () => ({ turnstileSiteKey: () => null }));

import { ConfirmIdentityDialog } from '@/components/auth/ConfirmIdentityDialog';

/** AUD-005: the step between a refused destructive action and its retry. */
describe('ConfirmIdentityDialog', () => {
  let container: HTMLDivElement;
  let root: Root;
  const onConfirmed = vi.fn(async () => {});
  const onCancel = vi.fn();

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.clearAllMocks();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  function render(email: string | null = 'me@example.com') {
    return act(async () => {
      root.render(
        createElement(ConfirmIdentityDialog, {
          open: true,
          email,
          action: 'delete your account',
          onCancel,
          onConfirmed,
        }),
      );
    });
  }

  function button(label: string) {
    return [...document.body.querySelectorAll('button')].find((candidate) => candidate.textContent === label)!;
  }

  async function typeCode(value: string) {
    const input = document.body.querySelector<HTMLInputElement>('input[autocomplete="one-time-code"]')!;
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setValue.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }

  it('says what it is for and where the code goes, and that nothing local changes', async () => {
    await render();

    const text = document.body.textContent ?? '';
    expect(text).toContain('To delete your account, enter a new sign-in code');
    expect(text).toContain('me@example.com');
    expect(text).toContain('nothing on this device changes');
  });

  it('sends a code to the account address, then retries the action once the code is verified', async () => {
    await render();

    await act(async () => button('Email me a code').click());
    expect(auth.sendEmailSignInCode).toHaveBeenCalledWith('me@example.com', undefined);

    await typeCode('123456');
    await act(async () => button('Confirm').click());

    expect(auth.verifyEmailSignInCode).toHaveBeenCalledWith('me@example.com', '123456');
    expect(onConfirmed).toHaveBeenCalledTimes(1);
    expect(auth.verifyEmailSignInCode.mock.invocationCallOrder[0]).toBeLessThan(
      onConfirmed.mock.invocationCallOrder[0]!,
    );
  });

  it('does not retry the action when the code is wrong', async () => {
    auth.verifyEmailSignInCode.mockRejectedValueOnce(new Error('Token has expired or is invalid'));
    await render();
    await act(async () => button('Email me a code').click());
    await typeCode('000000');

    await act(async () => button('Confirm').click());

    expect(onConfirmed).not.toHaveBeenCalled();
    expect(document.body.querySelector('[aria-live] p')).not.toBeNull();
  });

  it('asks for the whole code before checking it', async () => {
    await render();
    await act(async () => button('Email me a code').click());
    await typeCode('123');

    await act(async () => button('Confirm').click());

    expect(auth.verifyEmailSignInCode).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Enter the 6-digit code');
  });

  it('sends nothing without the account address', async () => {
    await render(null);

    await act(async () => button('Email me a code').click());

    expect(auth.sendEmailSignInCode).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Sign in again to continue');
  });
});
