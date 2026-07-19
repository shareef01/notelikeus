import { BrandMark } from '@/components/brand/BrandMark';
import { GoogleSignInButton } from '@/components/auth/GoogleSignInButton';
import { CloseIcon } from '@/components/icons/Icons';
import { CloudIcon, NotesIcon, SyncIcon } from '@/components/icons/Icons';
import { useAuthListener } from '@/hooks/useAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
import {
  createEmailPasswordAccount,
  signInWithEmailPassword,
} from '@/lib/auth/emailAuth';
import { signInWithGoogle } from '@/lib/auth/googleAuth';
import { useToastStore } from '@/store/toastStore';
import { useUiStore, type AuthMode } from '@/store/uiStore';
import { useEffect, useState } from 'react';

const COPY: Record<
  AuthMode,
  { title: string; subtitle: string; googleLabel: string; switchPrompt: string; switchMode: AuthMode; switchLabel: string }
> = {
  signin: {
    title: 'Welcome back',
    subtitle: 'Sign in to sync your notes across this browser and your Android device.',
    googleLabel: 'Sign in with Google',
    switchPrompt: "Don't have an account?",
    switchMode: 'signup',
    switchLabel: 'Create one',
  },
  signup: {
    title: 'Create your account',
    subtitle: 'Use Google to back up notes, sync with Android, and keep everything in one place.',
    googleLabel: 'Sign up with Google',
    switchPrompt: 'Already have an account?',
    switchMode: 'signin',
    switchLabel: 'Sign in',
  },
};

const FEATURES = [
  { icon: CloudIcon, text: 'Cloud backup with your Google account' },
  { icon: SyncIcon, text: 'Sync notes with the Android app' },
  { icon: NotesIcon, text: 'Keep writing offline — sync when you sign in' },
] as const;

interface AuthScreenProps {
  mode: AuthMode;
  /** When true, this is the mandatory startup gate: no close button, no skipping. */
  mandatory?: boolean;
}

export function AuthScreen({ mode, mandatory = false }: AuthScreenProps) {
  const closeAuthScreen = useUiStore((s) => s.closeAuthScreen);
  const openAuthScreen = useUiStore((s) => s.openAuthScreen);
  const { user, isReady } = useAuthListener();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [testEmail, setTestEmail] = useState(
    () => import.meta.env.VITE_TEST_LOGIN_EMAIL?.trim() ?? '',
  );
  const [testPassword, setTestPassword] = useState(
    () => import.meta.env.VITE_TEST_LOGIN_PASSWORD ?? '',
  );

  const copy = COPY[mode];

  useEffect(() => {
    if (isReady && user) {
      closeAuthScreen();
      useToastStore.getState().show(
        mode === 'signup' ? 'Account created — welcome to Notelikeus' : 'Signed in successfully',
      );
    }
  }, [isReady, user, closeAuthScreen, mode]);

  const handleGoogle = async () => {
    setError(null);
    setLoading(true);
    try {
      await signInWithGoogle();
    } catch (err) {
      setError(formatAuthError(err));
    } finally {
      setLoading(false);
    }
  };

  const handleTestSignIn = async (create: boolean, email: string, password: string) => {
    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      setError('Enter an email address.');
      return;
    }
    if (!password) {
      setError('Enter a password.');
      return;
    }
    if (create && password.length < 6) {
      setError('Password must be at least 6 characters.');
      return;
    }

    setError(null);
    setLoading(true);
    setTestEmail(trimmedEmail);
    setTestPassword(password);
    try {
      if (create) {
        await createEmailPasswordAccount(trimmedEmail, password);
      } else {
        await signInWithEmailPassword(trimmedEmail, password);
      }
    } catch (err) {
      setError(formatAuthError(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex min-h-0 flex-col bg-true-surface">
      <header className="flex shrink-0 items-center justify-end px-4 pt-safe">
        {mandatory ? null : (
          <button
            type="button"
            onClick={closeAuthScreen}
            className="rounded-full p-2 text-brand-muted transition-colors hover:bg-white/5 hover:text-brand-primary"
            aria-label="Close"
          >
            <CloseIcon size={20} />
          </button>
        )}
      </header>

      {/* Scrollable so Test login is reachable on short screens (was clipped by justify-center). */}
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-6 pb-safe">
        <div className="mx-auto flex w-full max-w-md flex-col py-6 sm:py-10">
          <div className="flex flex-col items-center text-center">
            <BrandMark size={56} />
            <h1 className="mt-5 text-2xl font-semibold text-brand-primary">{copy.title}</h1>
            <p className="mt-2 text-sm text-brand-muted">
              {mandatory
                ? 'Sign in to back up and sync your notes. Notes already on this device are safe and will appear once you sign in.'
                : copy.subtitle}
            </p>
          </div>

          {mandatory ? null : (
            <>
              <div className="mt-6 rounded-note border border-brand-outline/40 bg-true-surface-variant/30 p-1">
                <div className="grid grid-cols-2 gap-1">
                  {(['signin', 'signup'] as const).map((tab) => {
                    const active = mode === tab;
                    return (
                      <button
                        key={tab}
                        type="button"
                        onClick={() => openAuthScreen(tab)}
                        className={`rounded-[12px] px-4 py-2.5 text-sm font-semibold transition-colors ${
                          active
                            ? 'bg-true-surface text-brand-primary shadow-sm'
                            : 'text-brand-muted hover:text-brand-secondary'
                        }`}
                      >
                        {tab === 'signin' ? 'Sign in' : 'Sign up'}
                      </button>
                    );
                  })}
                </div>
              </div>

              <ul className="mt-6 space-y-3">
                {FEATURES.map(({ icon: Icon, text }) => (
                  <li key={text} className="flex items-start gap-3 text-sm text-brand-secondary">
                    <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-brand-primary/10 text-brand-primary">
                      <Icon size={16} />
                    </span>
                    <span className="pt-1">{text}</span>
                  </li>
                ))}
              </ul>
            </>
          )}

          <div className="mt-6 space-y-4">
            <GoogleSignInButton
              label={copy.googleLabel}
              onClick={() => void handleGoogle()}
              loading={loading}
            />

            {import.meta.env.DEV ? (
              <>
                <div className="relative flex items-center gap-3 py-1">
                  <div className="h-px flex-1 bg-brand-outline/40" />
                  <span className="text-xs font-medium uppercase tracking-wider text-brand-muted">or</span>
                  <div className="h-px flex-1 bg-brand-outline/40" />
                </div>

                <form
                  className="space-y-3 rounded-note border border-brand-outline/70 bg-true-surface-variant/40 p-4"
                  data-testid="test-login"
                  onSubmit={(e) => {
                    e.preventDefault();
                    const data = new FormData(e.currentTarget);
                    const email = String(data.get('email') ?? '');
                    const password = String(data.get('password') ?? '');
                    void handleTestSignIn(false, email, password);
                  }}
                >
                  <p className="text-center text-sm font-semibold text-brand-primary">Test login</p>
                  <p className="text-center text-xs text-brand-muted">
                    Use any email + password (min 6 characters). Create once, then Sign in.
                  </p>
                  <label className="block text-left text-xs font-medium text-brand-muted" htmlFor="test-login-email">
                    Email
                  </label>
                  <input
                    id="test-login-email"
                    name="email"
                    type="email"
                    autoComplete="username"
                    placeholder="test@example.com"
                    defaultValue={testEmail}
                    required
                    className="w-full rounded-note border border-brand-outline/50 bg-true-surface px-3 py-2.5 text-sm text-brand-primary outline-none focus:border-brand-primary"
                  />
                  <label className="block text-left text-xs font-medium text-brand-muted" htmlFor="test-login-password">
                    Password
                  </label>
                  <input
                    id="test-login-password"
                    name="password"
                    type="password"
                    autoComplete="new-password"
                    placeholder="At least 6 characters"
                    defaultValue={testPassword}
                    minLength={6}
                    required
                    className="w-full rounded-note border border-brand-outline/50 bg-true-surface px-3 py-2.5 text-sm text-brand-primary outline-none focus:border-brand-primary"
                  />
                  <div className="flex gap-2 pt-1">
                    <button
                      type="submit"
                      disabled={loading}
                      className="flex-1 rounded-note bg-brand-primary px-3 py-2.5 text-sm font-semibold text-true-surface disabled:opacity-50"
                    >
                      {loading ? 'Working…' : 'Sign in'}
                    </button>
                    <button
                      type="button"
                      disabled={loading}
                      onClick={(e) => {
                        const form = e.currentTarget.form;
                        if (!form) return;
                        const data = new FormData(form);
                        void handleTestSignIn(
                          true,
                          String(data.get('email') ?? ''),
                          String(data.get('password') ?? ''),
                        );
                      }}
                      className="flex-1 rounded-note border border-brand-outline/50 px-3 py-2.5 text-sm font-semibold text-brand-primary disabled:opacity-50"
                    >
                      {loading ? 'Working…' : 'Create account'}
                    </button>
                  </div>
                  {error ? (
                    <p className="rounded-note border border-red-900/50 bg-red-950/30 px-3 py-2 text-center text-sm text-red-200">
                      {error}
                    </p>
                  ) : null}
                </form>
              </>
            ) : error ? (
              <p className="rounded-note border border-red-900/50 bg-red-950/30 px-3 py-2 text-center text-sm text-red-200">
                {error}
              </p>
            ) : null}

            {mandatory ? null : (
              <button
                type="button"
                onClick={closeAuthScreen}
                className="w-full rounded-note border border-brand-outline/50 px-4 py-3 text-sm font-semibold text-brand-primary transition-colors hover:bg-white/5"
              >
                Continue without an account
              </button>
            )}
          </div>

          {mandatory ? null : (
            <p className="mt-8 text-center text-sm text-brand-muted">
              {copy.switchPrompt}{' '}
              <button
                type="button"
                onClick={() => openAuthScreen(copy.switchMode)}
                className="font-semibold text-brand-primary underline-offset-2 hover:underline"
              >
                {copy.switchLabel}
              </button>
            </p>
          )}

          <p className="mt-6 pb-8 text-center text-xs leading-relaxed text-brand-muted/80">
            By continuing, you agree that notes you choose to sync are stored in your Firebase
            account under your signed-in identity.
          </p>
        </div>
      </div>
    </div>
  );
}
