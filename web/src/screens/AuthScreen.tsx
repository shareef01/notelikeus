import { BrandMark } from '@/components/brand/BrandMark';
import { GoogleSignInButton } from '@/components/auth/GoogleSignInButton';
import { CloseIcon } from '@/components/icons/Icons';
import { CloudIcon, NotesIcon, SyncIcon } from '@/components/icons/Icons';
import { useAuthListener } from '@/hooks/useAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
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
}

export function AuthScreen({ mode }: AuthScreenProps) {
  const closeAuthScreen = useUiStore((s) => s.closeAuthScreen);
  const openAuthScreen = useUiStore((s) => s.openAuthScreen);
  const { user, isReady } = useAuthListener();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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

  return (
    <div className="fixed inset-0 z-[60] flex min-h-screen flex-col bg-true-black">
      <header className="flex items-center justify-end px-4 pt-safe">
        <button
          type="button"
          onClick={closeAuthScreen}
          className="flex size-11 items-center justify-center rounded-full text-brand-muted interactive-hover"
          aria-label="Close"
        >
          <CloseIcon size={22} />
        </button>
      </header>

      <div className="flex flex-1 flex-col items-center justify-center px-6 pb-safe">
        <div className="w-full max-w-md">
          <div className="flex flex-col items-center text-center">
            <BrandMark size={72} />
            <p className="mt-5 text-2xl font-bold tracking-tight text-brand-primary sm:text-3xl">
              {copy.title}
            </p>
            <p className="mt-2 max-w-sm text-sm leading-relaxed text-brand-muted sm:text-base">
              {copy.subtitle}
            </p>
          </div>

          <div className="mt-8 rounded-note border border-brand-outline/40 bg-true-surface-variant/30 p-1">
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

          <ul className="mt-8 space-y-3">
            {FEATURES.map(({ icon: Icon, text }) => (
              <li key={text} className="flex items-start gap-3 text-sm text-brand-secondary">
                <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-brand-primary/10 text-brand-primary">
                  <Icon size={16} />
                </span>
                <span className="pt-1">{text}</span>
              </li>
            ))}
          </ul>

          <div className="mt-8 space-y-4">
            <GoogleSignInButton
              label={copy.googleLabel}
              onClick={() => void handleGoogle()}
              loading={loading}
            />

            {error ? (
              <p className="rounded-note border border-red-900/50 bg-red-950/30 px-4 py-3 text-center text-sm text-red-200">
                {error}
              </p>
            ) : null}

            <div className="relative flex items-center gap-3 py-1">
              <div className="h-px flex-1 bg-brand-outline/40" />
              <span className="text-xs font-medium uppercase tracking-wider text-brand-muted">or</span>
              <div className="h-px flex-1 bg-brand-outline/40" />
            </div>

            <button
              type="button"
              onClick={closeAuthScreen}
              className="w-full rounded-note border border-brand-outline/50 px-4 py-3 text-sm font-semibold text-brand-primary transition-colors interactive-hover"
            >
              Continue without an account
            </button>
          </div>

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

          <p className="mt-6 text-center text-xs leading-relaxed text-brand-muted/80">
            By continuing, you agree that notes you choose to sync are stored in your Firebase
            account under your Google identity.
          </p>
        </div>
      </div>
    </div>
  );
}
