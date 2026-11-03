import { TurnstileWidget } from '@/components/auth/TurnstileWidget';
import {
  CAPTCHA_REJECTED_MESSAGE,
  EMAIL_CODE_LENGTH,
  RESEND_COOLDOWN_SECONDS,
} from '@/components/auth/EmailCodeSignIn';
import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogDangerButtonClass,
  dialogPrimaryButtonClass,
} from '@/components/ui/ModalDialog';
import { ensureSupabaseAuthStarted } from '@/hooks/useAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
import { turnstileSiteKey } from '@/lib/auth/turnstile';
import { ERROR_ALERT } from '@/lib/ui/alertStyles';
import { useEffect, useId, useState } from 'react';

interface ConfirmIdentityDialogProps {
  open: boolean;
  /** The signed-in account's address. The code goes here and nowhere else. */
  email: string | null;
  /** What is being confirmed, e.g. "delete your account". */
  action: string;
  onCancel: () => void;
  /** Runs once the code is verified, with the new session; the caller closes the dialog. */
  onConfirmed: () => Promise<void>;
}

/**
 * "Confirm it's you" before an irreversible action (AUD-005).
 *
 * The server refuses to delete the account or wipe its cloud data unless this session signed in within
 * the last ten minutes. A new emailed code for the same account starts a fresh session, after which
 * [onConfirmed] retries the action. Same account, so nothing local changes: it is not a sign-out.
 */
export function ConfirmIdentityDialog({ open, email, action, onCancel, onConfirmed }: ConfirmIdentityDialogProps) {
  const [step, setStep] = useState<'send' | 'code'>('send');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState<'send' | 'verify' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cooldown, setCooldown] = useState(0);
  const [siteKey] = useState(turnstileSiteKey);
  const [captchaToken, setCaptchaToken] = useState<string | null>(null);
  const [captchaReset, setCaptchaReset] = useState(0);
  const titleId = useId();
  const codeId = useId();

  // A reopened dialog starts over: an old code is no use to a new attempt.
  useEffect(() => {
    if (!open) {
      setStep('send');
      setCode('');
      setError(null);
      setBusy(null);
    }
  }, [open]);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => setCooldown((value) => Math.max(0, value - 1)), 1000);
    return () => window.clearTimeout(timer);
  }, [cooldown]);

  const send = async () => {
    if (!email) {
      setError('Sign in again to continue.');
      return;
    }
    if (siteKey && !captchaToken) {
      setError('Wait for the security check below to finish, then try again.');
      return;
    }
    setBusy('send');
    setError(null);
    try {
      await ensureSupabaseAuthStarted();
      const { sendEmailSignInCode } = await import('@/lib/auth/accountAuth');
      await sendEmailSignInCode(email, captchaToken ?? undefined);
      setCooldown(RESEND_COOLDOWN_SECONDS);
      setCode('');
      setStep('code');
    } catch (err) {
      setError(formatAuthError(err));
    } finally {
      setBusy(null);
      // A token is good for one request, accepted or not.
      if (siteKey) setCaptchaReset((value) => value + 1);
    }
  };

  const verify = async () => {
    if (!email) return;
    if (code.trim().length !== EMAIL_CODE_LENGTH) {
      setError(`Enter the ${EMAIL_CODE_LENGTH}-digit code from the email.`);
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
      setBusy(null);
      return;
    }
    try {
      await onConfirmed();
    } finally {
      setBusy(null);
    }
  };

  return (
    <ModalDialog open={open} onClose={busy ? () => {} : onCancel} ariaLabelledBy={titleId}>
      <h2 id={titleId} className="text-lg font-semibold">
        Confirm it’s you
      </h2>
      <p className="mt-2 text-sm text-brand-muted">
        To {action}, enter a new sign-in code. We’ll email it to{' '}
        <span className="font-semibold">{email ?? 'your address'}</span>. You stay signed in, and
        nothing on this device changes.
      </p>
      {step === 'code' && (
        <div className="mt-4">
          <label htmlFor={codeId} className="block text-sm">
            Code from the email
          </label>
          <input
            id={codeId}
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={EMAIL_CODE_LENGTH}
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
            className="mt-1 w-full rounded-note border border-brand-muted/40 bg-transparent px-3 py-2 text-sm tracking-widest"
          />
        </div>
      )}
      <div aria-live="polite" aria-atomic="true">
        {error && <p className={`${ERROR_ALERT} mt-3 px-3 py-2 text-sm`}>{error}</p>}
      </div>
      {siteKey ? (
        <div className="mt-3">
          <TurnstileWidget
            siteKey={siteKey}
            resetKey={captchaReset}
            onToken={setCaptchaToken}
            onLoadError={() => setError('The security check could not load. Check your connection and try again.')}
            onChallengeError={() => setError(CAPTCHA_REJECTED_MESSAGE)}
          />
        </div>
      ) : null}
      <div className="mt-4 flex flex-wrap justify-end gap-2">
        <button type="button" onClick={onCancel} disabled={busy !== null} className={dialogCancelButtonClass}>
          Cancel
        </button>
        {step === 'send' ? (
          <button
            type="button"
            onClick={() => void send()}
            disabled={busy !== null}
            className={dialogPrimaryButtonClass}
          >
            {busy === 'send' ? 'Sending…' : 'Email me a code'}
          </button>
        ) : (
          <>
            <button
              type="button"
              onClick={() => void send()}
              disabled={busy !== null || cooldown > 0}
              className={dialogCancelButtonClass}
            >
              {cooldown > 0 ? `Resend in ${cooldown}s` : 'Resend code'}
            </button>
            <button
              type="button"
              onClick={() => void verify()}
              disabled={busy !== null}
              className={dialogDangerButtonClass}
            >
              {busy === 'verify' ? 'Confirming…' : 'Confirm'}
            </button>
          </>
        )}
      </div>
    </ModalDialog>
  );
}
