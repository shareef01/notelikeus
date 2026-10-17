import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';

const sendEmailSignInCode = vi.fn<(email: string, captchaToken?: string) => Promise<void>>();
const verifyEmailSignInCode = vi.fn<(email: string, code: string) => Promise<void>>();
const ensureSupabaseAuthStarted = vi.fn(async () => {});

vi.mock('@/lib/auth/accountAuth', () => ({
  sendEmailSignInCode: (email: string, captchaToken?: string) =>
    captchaToken === undefined
      ? sendEmailSignInCode(email)
      : sendEmailSignInCode(email, captchaToken),
  verifyEmailSignInCode: (email: string, code: string) => verifyEmailSignInCode(email, code),
}));
vi.mock('@/hooks/useAuth', () => ({
  ensureSupabaseAuthStarted: () => ensureSupabaseAuthStarted(),
  useAuthListener: () => ({ user: null, userId: null, isReady: true, isGuest: false }),
}));

import { AuthScreen } from '@/screens/AuthScreen';
import { CAPTCHA_REJECTED_MESSAGE, RESEND_COOLDOWN_SECONDS } from '@/components/auth/EmailCodeSignIn';
import type { TurnstileApi } from '@/lib/auth/turnstile';

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

/**
 * With a Turnstile site key (hosted builds), every code request carries a fresh Turnstile token:
 * the hosted project refuses `/auth/v1/otp` without one (audit S-02).
 */
describe('AuthScreen email-code sign-in with Turnstile', () => {
  let container: HTMLDivElement;
  let root: Root;
  let issueToken: ((token: string) => void) | null;
  let failCheck: (() => void) | null;
  let render: ReturnType<typeof vi.fn>;
  let reset: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.stubEnv('VITE_TURNSTILE_SITE_KEY', 'site-key');
    sendEmailSignInCode.mockReset().mockResolvedValue(undefined);
    issueToken = null;
    failCheck = null;
    render = vi.fn(
      (
        _el: HTMLElement,
        options: { sitekey: string; callback?: (t: string) => void; 'error-callback'?: () => void },
      ) => {
        issueToken = options.callback ?? null;
        failCheck = options['error-callback'] ?? null;
        return 'widget-1';
      },
    );
    reset = vi.fn();
    window.turnstile = { render, reset, remove: vi.fn() } as unknown as TurnstileApi;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root.render(createElement(AuthScreen, { mode: 'signin' }));
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    act(() => root.unmount());
    container.remove();
    delete window.turnstile;
    vi.unstubAllEnvs();
  });

  it('renders the check with the build’s site key', () => {
    expect(render).toHaveBeenCalledTimes(1);
    expect(render.mock.calls[0][1]).toMatchObject({ sitekey: 'site-key' });
  });

  it('does not request a code before the check has passed', async () => {
    await sendCodeTo('alice@example.com');

    expect(sendEmailSignInCode).not.toHaveBeenCalled();
    expect(container.querySelector('[aria-live]')?.textContent).toContain(
      'Wait for the security check below to finish',
    );
  });

  it('says what to try when the check does not pass, and clears it once a retry passes', async () => {
    act(() => failCheck?.());
    expect(container.querySelector('[aria-live]')?.textContent).toContain(CAPTCHA_REJECTED_MESSAGE);

    await sendCodeTo('alice@example.com');
    expect(sendEmailSignInCode).not.toHaveBeenCalled();
    expect(container.querySelector('[aria-live]')?.textContent).toContain(CAPTCHA_REJECTED_MESSAGE);

    act(() => issueToken?.('token-1'));
    expect(container.querySelector('[aria-live]')?.textContent).not.toContain(CAPTCHA_REJECTED_MESSAGE);
    await sendCodeTo('alice@example.com');
    expect(sendEmailSignInCode).toHaveBeenCalledWith('alice@example.com', 'token-1');
  });

  it('sends the token with the code request, then runs a new check for the next one', async () => {
    vi.useFakeTimers();
    act(() => issueToken?.('token-1'));
    await sendCodeTo('alice@example.com');

    expect(sendEmailSignInCode).toHaveBeenCalledWith('alice@example.com', 'token-1');
    expect(reset).toHaveBeenCalledWith('widget-1');

    // The spent token is not reused for "Resend code".
    for (let i = 0; i < RESEND_COOLDOWN_SECONDS; i += 1) {
      act(() => {
        vi.advanceTimersByTime(1000);
      });
    }
    await click(buttonNamed('Resend code'));
    expect(sendEmailSignInCode).toHaveBeenCalledTimes(1);

    act(() => issueToken?.('token-2'));
    await click(buttonNamed('Resend code'));
    expect(sendEmailSignInCode).toHaveBeenLastCalledWith('alice@example.com', 'token-2');
  });
});
