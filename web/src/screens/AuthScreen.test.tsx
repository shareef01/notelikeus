import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';

const sendEmailSignInCode = vi.fn<(email: string) => Promise<void>>();
const verifyEmailSignInCode = vi.fn<(email: string, code: string) => Promise<void>>();
const ensureSupabaseAuthStarted = vi.fn(async () => {});

vi.mock('@/lib/auth/accountAuth', () => ({
  sendEmailSignInCode: (email: string) => sendEmailSignInCode(email),
  verifyEmailSignInCode: (email: string, code: string) => verifyEmailSignInCode(email, code),
}));
vi.mock('@/hooks/useAuth', () => ({
  ensureSupabaseAuthStarted: () => ensureSupabaseAuthStarted(),
  useAuthListener: () => ({ user: null, userId: null, isReady: true, isGuest: false }),
}));

import { AuthScreen } from '@/screens/AuthScreen';
import { RESEND_COOLDOWN_SECONDS } from '@/components/auth/EmailCodeSignIn';

function authError(message: string, status: number, code: string): Error {
  return Object.assign(new Error(message), { name: 'AuthError', status, code });
}

/** Sets a controlled input's value the way React's onChange sees it. */
function typeInto(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  act(() => {
    setter?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

function buttonNamed(name: string | RegExp): HTMLButtonElement {
  const match = Array.from(document.querySelectorAll('button')).find((button) => {
    const text = button.textContent?.trim() ?? '';
    return typeof name === 'string' ? text === name : name.test(text);
  });
  if (!match) throw new Error(`No button named ${String(name)}`);
  return match;
}

function inputLabelled(text: string): HTMLInputElement {
  const label = Array.from(document.querySelectorAll('label')).find(
    (candidate) => candidate.textContent?.trim() === text,
  );
  const input = label ? document.getElementById(label.htmlFor) : null;
  if (!(input instanceof HTMLInputElement)) throw new Error(`No input labelled ${text}`);
  return input;
}

async function click(button: HTMLButtonElement) {
  await act(async () => {
    button.click();
  });
}

async function submit(form: HTMLFormElement | null) {
  await act(async () => {
    form?.requestSubmit();
  });
}

async function sendCodeTo(email: string) {
  typeInto(inputLabelled('Email'), email);
  await click(buttonNamed('Send code'));
}

describe('AuthScreen email-code sign-in', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    sendEmailSignInCode.mockReset().mockResolvedValue(undefined);
    verifyEmailSignInCode.mockReset().mockResolvedValue(undefined);
    ensureSupabaseAuthStarted.mockClear();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    act(() => {
      root.render(createElement(AuthScreen, { mode: 'signin' }));
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    act(() => root.unmount());
    container.remove();
  });

  it('never mentions Google', () => {
    expect(container.textContent?.toLowerCase()).not.toContain('google');
  });

  it('sends a code, then moves focus to a numeric one-time-code field', async () => {
    await sendCodeTo('  alice@example.com ');

    expect(ensureSupabaseAuthStarted).toHaveBeenCalled();
    expect(sendEmailSignInCode).toHaveBeenCalledWith('alice@example.com');

    const codeInput = inputLabelled('6-digit code');
    expect(codeInput.inputMode).toBe('numeric');
    expect(codeInput.autocomplete).toBe('one-time-code');
    expect(codeInput.maxLength).toBe(6);
    expect(document.activeElement).toBe(codeInput);
    expect(container.textContent).toContain('We sent a 6-digit code to alice@example.com.');
  });

  it('rejects an invalid email without calling Supabase', async () => {
    await sendCodeTo('not-an-email');

    expect(sendEmailSignInCode).not.toHaveBeenCalled();
    expect(container.querySelector('[aria-live]')?.textContent).toContain(
      'Enter a valid email address.',
    );
  });

  it('verifies the typed code against the same email', async () => {
    await sendCodeTo('alice@example.com');
    const codeInput = inputLabelled('6-digit code');

    typeInto(codeInput, '12a 34-56');
    expect(codeInput.value).toBe('123456');
    await submit(codeInput.form);

    expect(verifyEmailSignInCode).toHaveBeenCalledWith('alice@example.com', '123456');
  });

  it('asks for all six digits before verifying', async () => {
    await sendCodeTo('alice@example.com');
    const codeInput = inputLabelled('6-digit code');
    typeInto(codeInput, '123');
    await click(codeInput.form!.querySelector('button[type="submit"]') as HTMLButtonElement);

    expect(verifyEmailSignInCode).not.toHaveBeenCalled();
    expect(container.querySelector('[aria-live]')?.textContent).toContain(
      'Enter the 6-digit code from the email.',
    );
  });

  it('announces a wrong or expired code in the live region and keeps the code step', async () => {
    verifyEmailSignInCode.mockRejectedValueOnce(
      authError('Token has expired or is invalid', 403, 'otp_expired'),
    );
    await sendCodeTo('alice@example.com');
    const codeInput = inputLabelled('6-digit code');
    typeInto(codeInput, '000000');
    await submit(codeInput.form);

    expect(container.querySelector('[aria-live]')?.textContent).toContain(
      'That code is wrong or has expired.',
    );
    expect(codeInput.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(codeInput);
  });

  it('shows a friendly message when Supabase rate-limits code emails', async () => {
    sendEmailSignInCode.mockRejectedValueOnce(
      authError('email rate limit exceeded', 429, 'over_email_send_rate_limit'),
    );
    await sendCodeTo('alice@example.com');

    expect(container.querySelector('[aria-live]')?.textContent).toContain(
      'Too many codes requested.',
    );
    // Still on the email step.
    expect(inputLabelled('Email').value).toBe('alice@example.com');
  });

  it('holds "Resend code" for a cooldown, then resends to the same email', async () => {
    vi.useFakeTimers();
    await sendCodeTo('alice@example.com');

    const resend = buttonNamed(/^Resend code/);
    expect(resend.disabled).toBe(true);
    expect(resend.textContent).toBe(`Resend code in ${RESEND_COOLDOWN_SECONDS}s`);

    for (let i = 0; i < RESEND_COOLDOWN_SECONDS; i += 1) {
      act(() => {
        vi.advanceTimersByTime(1000);
      });
    }
    expect(resend.disabled).toBe(false);
    expect(resend.textContent).toBe('Resend code');

    await click(resend);
    expect(sendEmailSignInCode).toHaveBeenCalledTimes(2);
    expect(sendEmailSignInCode).toHaveBeenLastCalledWith('alice@example.com');
    expect(buttonNamed(/^Resend code/).disabled).toBe(true);
  });

  it('goes back to the email step with "Use a different email"', async () => {
    await sendCodeTo('alice@example.com');
    await click(buttonNamed('Use a different email'));

    const emailInput = inputLabelled('Email');
    expect(emailInput.value).toBe('alice@example.com');
    expect(document.activeElement).toBe(emailInput);
    expect(() => inputLabelled('6-digit code')).toThrow();
  });
});
