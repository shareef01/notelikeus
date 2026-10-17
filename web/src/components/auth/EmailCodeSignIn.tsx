import { TurnstileWidget } from '@/components/auth/TurnstileWidget';
import { ensureSupabaseAuthStarted } from '@/hooks/useAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
import { turnstileSiteKey } from '@/lib/auth/turnstile';
import { ERROR_ALERT } from '@/lib/ui/alertStyles';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import { useEffect, useId, useRef, useState } from 'react';

/** Matches `auth.email.otp_length` in supabase/config.toml. */
export const EMAIL_CODE_LENGTH = 6;
/** Seconds before "Resend code" is offered again (Supabase's default minimum gap is 60 s). */
export const RESEND_COOLDOWN_SECONDS = 60;

/**
 * Turnstile ran and did not pass. Waiting does not help, so say what usually does: Cloudflare
 * distrusts some browser sessions, most often behind a VPN, strict privacy settings or a blocker.
 */
export const CAPTCHA_REJECTED_MESSAGE =
  "The security check didn't pass in this browser. Turn off a VPN or ad blocker for this site, or try another browser.";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const INPUT_CLASS =
  'w-full rounded-note border border-brand-outline/50 bg-true-surface px-3 py-2.5 text-sm text-brand-primary outline-none focus:border-brand-primary disabled:opacity-60';
const PRIMARY_BUTTON_CLASS = `w-full rounded-note bg-brand-primary px-4 py-3 text-sm font-semibold text-true-surface transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50 ${CHROME_FOCUS}`;
const LINK_BUTTON_CLASS = `rounded font-semibold text-brand-primary underline-offset-2 hover:underline disabled:cursor-not-allowed disabled:text-brand-muted disabled:no-underline ${CHROME_FOCUS}`;

interface EmailCodeSignInProps {
  /** Label of the final button ("Sign in" / "Create account"). Both create the account on first use. */
  submitLabel: string;
  /** Lets the parent disable its other actions while a request is in flight. */
  onBusyChange?: (busy: boolean) => void;
}

type Step = 'email' | 'code';

/**
 * Passwordless sign-in: the user enters their email, Supabase emails a six-digit code, and the
 * user types it back here. The auth listener picks up the new session and closes the auth screen.
 */
export function EmailCodeSignIn({ submitLabel, onBusyChange }: EmailCodeSignInProps) {
  const [step, setStep] = useState<Step>('email');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState<'send' | 'verify' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [cooldown, setCooldown] = useState(0);
  // Null in builds without CAPTCHA; then no widget renders and no token is sent.
  const [siteKey] = useState(turnstileSiteKey);
  const [captchaToken, setCaptchaToken] = useState<string | null>(null);
  const [captchaFailed, setCaptchaFailed] = useState(false);
  const [captchaRejected, setCaptchaRejected] = useState(false);
  const [captchaReset, setCaptchaReset] = useState(0);
  const codeInputRef = useRef<HTMLInputElement>(null);
  const emailInputRef = useRef<HTMLInputElement>(null);
  const moveFocusTo = useRef<'email' | 'code' | null>(null);
  const ids = useId();
  const emailId = `${ids}-email`;
  const codeId = `${ids}-code`;
  const codeHintId = `${ids}-code-hint`;
  const errorId = `${ids}-error`;

  useEffect(() => {
    onBusyChange?.(busy !== null);
  }, [busy, onBusyChange]);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => setCooldown((value) => Math.max(0, value - 1)), 1000);
    return () => window.clearTimeout(timer);
  }, [cooldown]);

  useEffect(() => {
    if (moveFocusTo.current === 'code' && step === 'code') {
      codeInputRef.current?.focus();
    } else if (moveFocusTo.current === 'email' && step === 'email') {
      emailInputRef.current?.focus();
    }
    moveFocusTo.current = null;
  }, [step]);

  const sendCode = async (address: string): Promise<boolean> => {
    if (siteKey && !captchaToken) {
      setError(
        captchaFailed
          ? 'The security check could not load. Check your connection and reload the page.'
          : captchaRejected
            ? CAPTCHA_REJECTED_MESSAGE
            : 'Wait for the security check below to finish, then try again.',
      );
      return false;
    }
    setBusy('send');
    setError(null);
    try {
      await ensureSupabaseAuthStarted();
      const { sendEmailSignInCode } = await import('@/lib/auth/accountAuth');
      await sendEmailSignInCode(address, captchaToken ?? undefined);
      setCooldown(RESEND_COOLDOWN_SECONDS);
      return true;
    } catch (err) {
      setError(formatAuthError(err));
      return false;
    } finally {
      setBusy(null);
      // A token is good for one request, accepted or not: run a new check for the next one.
      if (siteKey) setCaptchaReset((value) => value + 1);
    }
  };

  const handleSend = async () => {
    const trimmed = email.trim();
    if (!EMAIL_PATTERN.test(trimmed)) {
      setError('Enter a valid email address.');
      return;
    }
    setEmail(trimmed);
    if (await sendCode(trimmed)) {
      setCode('');
      // No notice here: the hint under the code field, read with it on focus, says where it went.
      setNotice(null);
      moveFocusTo.current = 'code';
      setStep('code');
    }
  };

  const handleResend = async () => {
    if (cooldown > 0 || busy) return;
    setNotice(null);
    if (await sendCode(email)) {
      setCode('');
      setNotice(`We sent a new code to ${email}.`);
      codeInputRef.current?.focus();
    }
  };

  const handleVerify = async () => {
    if (code.length !== EMAIL_CODE_LENGTH) {
      setError(`Enter the ${EMAIL_CODE_LENGTH}-digit code from the email.`);
      codeInputRef.current?.focus();
      return;
    }
    setBusy('verify');
    setError(null);
    try {
      await ensureSupabaseAuthStarted();
      const { verifyEmailSignInCode } = await import('@/lib/auth/accountAuth');
      await verifyEmailSignInCode(email, code);
    } catch (err) {
      setError(formatAuthError(err));
      codeInputRef.current?.focus();
    } finally {
      setBusy(null);
    }
  };

  const handleChangeEmail = () => {
    setCode('');
    setError(null);
    setNotice(null);
    moveFocusTo.current = 'email';
    setStep('email');
  };

  // Rendered inside each step's form, right under its field, so an error sits next to what it is
  // about instead of below the links and the security check.
  const messages = (
    <div aria-live="polite" aria-atomic="true">
      {error ? (
        <p id={errorId} className={`${ERROR_ALERT} px-3 py-2 text-center text-sm`}>
          {error}
        </p>
      ) : notice ? (
        <p className="text-center text-sm text-brand-secondary">{notice}</p>
      ) : null}
    </div>
  );

  return (
    <div className="space-y-3" data-testid="email-code-sign-in">
      {step === 'email' ? (
        <form
          className="space-y-3"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            void handleSend();
          }}
        >
          <label className="block text-left text-xs font-medium text-brand-muted" htmlFor={emailId}>
            Email
          </label>
          <input
            ref={emailInputRef}
            id={emailId}
            name="email"
            type="email"
            inputMode="email"
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            placeholder="you@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={busy !== null}
            required
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? errorId : undefined}
            className={INPUT_CLASS}
          />
          {messages}
          <button type="submit" disabled={busy !== null} className={PRIMARY_BUTTON_CLASS}>
            {busy === 'send' ? 'Sending code…' : 'Send code'}
          </button>
          <p className="text-center text-xs leading-relaxed text-brand-muted">
            We&apos;ll email you a {EMAIL_CODE_LENGTH}-digit code. No password needed.
          </p>
        </form>
      ) : (
        <form
          className="space-y-3"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            void handleVerify();
          }}
        >
          <label className="block text-left text-xs font-medium text-brand-muted" htmlFor={codeId}>
            {EMAIL_CODE_LENGTH}-digit code
          </label>
          <input
            ref={codeInputRef}
            id={codeId}
            name="code"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern={`[0-9]{${EMAIL_CODE_LENGTH}}`}
            maxLength={EMAIL_CODE_LENGTH}
            placeholder={'0'.repeat(EMAIL_CODE_LENGTH)}
            value={code}
            onChange={(e) =>
              setCode(e.target.value.replace(/\D/g, '').slice(0, EMAIL_CODE_LENGTH))
            }
            disabled={busy === 'verify'}
            required
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${codeHintId} ${errorId}` : codeHintId}
            className={`${INPUT_CLASS} text-center font-mono text-lg tracking-[0.5em] indent-[0.5em]`}
          />
          <p id={codeHintId} className="text-center text-xs leading-relaxed text-brand-muted">
            Sent to <span className="font-semibold text-brand-secondary">{email}</span>. Check spam
            if it hasn&apos;t arrived.
          </p>
          {messages}
          <button type="submit" disabled={busy !== null} className={PRIMARY_BUTTON_CLASS}>
            {busy === 'verify' ? 'Checking code…' : submitLabel}
          </button>
          <div className="flex items-center justify-between gap-3 text-sm">
            <button
              type="button"
              onClick={handleChangeEmail}
              disabled={busy !== null}
              className={LINK_BUTTON_CLASS}
            >
              Use a different email
            </button>
            <button
              type="button"
              onClick={() => void handleResend()}
              disabled={busy !== null || cooldown > 0}
              className={LINK_BUTTON_CLASS}
            >
              {busy === 'send'
                ? 'Sending…'
                : cooldown > 0
                  ? `Resend code in ${cooldown}s`
                  : 'Resend code'}
            </button>
          </div>
        </form>
      )}

      {siteKey ? (
        <TurnstileWidget
          siteKey={siteKey}
          resetKey={captchaReset}
          onToken={(token) => {
            setCaptchaToken(token);
            if (token) {
              setCaptchaFailed(false);
              setCaptchaRejected(false);
              setError((current) => (current === CAPTCHA_REJECTED_MESSAGE ? null : current));
            }
          }}
          onLoadError={() => setCaptchaFailed(true)}
          onChallengeError={() => {
            setCaptchaRejected(true);
            setError(CAPTCHA_REJECTED_MESSAGE);
          }}
        />
      ) : null}
    </div>
  );
}
