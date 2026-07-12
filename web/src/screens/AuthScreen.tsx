import { BrandMark } from '@/components/brand/BrandMark';
import { GoogleSignInButton } from '@/components/auth/GoogleSignInButton';
import { CloseIcon } from '@/components/icons/Icons';
import { CloudIcon, NotesIcon, SyncIcon, LockIcon } from '@/components/icons/Icons';
import { useAuthListener } from '@/hooks/useAuth';
import { formatAuthError } from '@/lib/auth/authErrors';
import { signInWithGoogle } from '@/lib/auth/googleAuth';
import { useToastStore } from '@/store/toastStore';
import { useUiStore, type AuthMode } from '@/store/uiStore';
import { useEffect, useState } from 'react';

const COPY: Record<
  AuthMode,
  {
    title: string;
    subtitle: string;
    googleLabel: string;
    switchPrompt: string;
    switchMode: AuthMode;
    switchLabel: string;
  }
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
    subtitle:
      'Use Google to back up notes, sync with Android, and keep everything in one place.',
    googleLabel: 'Sign up with Google',
    switchPrompt: 'Already have an account?',
    switchMode: 'signin',
    switchLabel: 'Sign in',
  },
};

const FEATURES = [
  {
    icon: CloudIcon,
    title: 'Cloud backup',
    text: 'Your notes are safely backed up with your Google account',
  },
  {
    icon: SyncIcon,
    title: 'Cross-device sync',
    text: 'Seamlessly sync between web and your Android device',
  },
  {
    icon: NotesIcon,
    title: 'Offline first',
    text: 'Keep writing offline — sync automatically when you connect',
  },
  {
    icon: LockIcon,
    title: 'End-to-end security',
    text: 'Locked notes stay private and are never uploaded',
  },
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
  const [animateIn, setAnimateIn] = useState(false);

  const copy = COPY[mode];

  useEffect(() => {
    requestAnimationFrame(() => setAnimateIn(true));
  }, []);

  useEffect(() => {
    if (isReady && user) {
      closeAuthScreen();
      useToastStore.getState().show(
        mode === 'signup'
          ? 'Account created — welcome to Notelikeus'
          : 'Signed in successfully',
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
    <div className="fixed inset-0 z-[60] flex min-h-screen flex-col overflow-y-auto bg-[#09090B]">
      {/* Subtle background decoration */}
      <div className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute -left-32 -top-32 h-96 w-96 rounded-full bg-brand-primary/[0.02] blur-3xl" />
        <div className="absolute -bottom-32 -right-32 h-96 w-96 rounded-full bg-brand-primary/[0.015] blur-3xl" />
      </div>

      <header className="relative z-10 flex items-center justify-end px-4 pt-safe">
        <button
          type="button"
          onClick={closeAuthScreen}
          className="flex size-11 items-center justify-center rounded-full text-brand-muted/60 transition-colors hover:bg-white/5 hover:text-brand-primary"
          aria-label="Close"
        >
          <CloseIcon size={22} />
        </button>
      </header>

      <div className="relative z-10 flex flex-1 flex-col items-center justify-center px-5 pb-safe sm:px-6">
        <div
          className={`w-full max-w-sm transition-all duration-700 ease-out ${
            animateIn ? 'translate-y-0 opacity-100' : 'translate-y-6 opacity-0'
          }`}
        >
          {/* Brand hero */}
          <div className="flex flex-col items-center text-center">
            <div className="relative">
              <div className="absolute -inset-4 rounded-full bg-brand-primary/5 blur-xl" />
              <BrandMark size={80} />
            </div>
            <p className="mt-6 text-2xl font-bold tracking-tight text-brand-primary sm:text-[28px]">
              {copy.title}
            </p>
            <p className="mt-2 max-w-xs text-sm leading-relaxed text-brand-muted sm:text-base">
              {copy.subtitle}
            </p>
          </div>

          {/* Tab toggle */}
          <div className="mt-7 rounded-2xl border border-white/[0.06] bg-white/[0.03] p-1">
            <div className="grid grid-cols-2 gap-1">
              {(['signin', 'signup'] as const).map((tab) => {
                const active = mode === tab;
                return (
                  <button
                    key={tab}
                    type="button"
                    onClick={() => openAuthScreen(tab)}
                    className={`rounded-xl px-4 py-2.5 text-sm font-semibold transition-all ${
                      active
                        ? 'bg-white/10 text-brand-primary shadow-sm'
                        : 'text-brand-muted/60 hover:text-brand-primary'
                    }`}
                  >
                    {tab === 'signin' ? 'Sign in' : 'Sign up'}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Feature list */}
          <div className="mt-7 space-y-3">
            {FEATURES.map(({ icon: Icon, title, text }) => (
              <div
                key={title}
                className="flex items-start gap-3.5 rounded-xl border border-white/[0.04] bg-white/[0.02] px-4 py-3.5 transition-colors hover:bg-white/[0.04]"
              >
                <span className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-primary/8 text-brand-primary">
                  <Icon size={17} />
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-brand-primary">{title}</p>
                  <p className="mt-0.5 text-xs leading-relaxed text-brand-muted/70">{text}</p>
                </div>
              </div>
            ))}
          </div>

          {/* Auth actions */}
          <div className="mt-7 space-y-4">
            <GoogleSignInButton
              label={copy.googleLabel}
              onClick={() => void handleGoogle()}
              loading={loading}
            />

            {error ? (
              <p className="rounded-xl border border-red-900/50 bg-red-950/30 px-4 py-3 text-center text-sm text-red-200">
                {error}
              </p>
            ) : null}

            <div className="relative flex items-center gap-3 py-0.5">
              <div className="h-px flex-1 bg-white/[0.06]" />
              <span className="text-xs font-medium uppercase tracking-wider text-brand-muted/40">
                or
              </span>
              <div className="h-px flex-1 bg-white/[0.06]" />
            </div>

            <button
              type="button"
              onClick={closeAuthScreen}
              className="w-full rounded-xl border border-white/[0.08] px-4 py-3 text-sm font-semibold text-brand-muted transition-all hover:bg-white/[0.03] hover:text-brand-primary active:scale-[0.98]"
            >
              Continue without an account
            </button>
          </div>

          {/* Switch mode */}
          <p className="mt-7 text-center text-sm text-brand-muted/60">
            {copy.switchPrompt}{' '}
            <button
              type="button"
              onClick={() => openAuthScreen(copy.switchMode)}
              className="font-semibold text-brand-primary underline-offset-2 transition-colors hover:underline"
            >
              {copy.switchLabel}
            </button>
          </p>

          {/* Terms */}
          <p className="mt-6 text-center text-[11px] leading-relaxed text-brand-muted/40">
            By continuing, you agree that synced notes are stored in your Firebase account
            under your Google identity. Locked notes are never uploaded.
          </p>
        </div>
      </div>
    </div>
  );
}
