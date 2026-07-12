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
  }
> = {
  signin: {
    title: 'Welcome back',
    subtitle: 'Sign in to sync your notes across this browser and your Android device.',
    googleLabel: 'Sign in with Google',
  },
  signup: {
    title: 'Create your account',
    subtitle:
      'Use Google to back up notes, sync with Android, and keep everything in one place.',
    googleLabel: 'Sign up with Google',
  },
};

const FEATURES = [
  {
    icon: CloudIcon,
    title: 'Cloud backup',
    text: 'Your notes are safely stored and backed up with your Google account',
  },
  {
    icon: SyncIcon,
    title: 'Cross-device sync',
    text: 'Seamlessly sync between web and your Android device',
  },
  {
    icon: NotesIcon,
    title: 'Rich notes',
    text: 'Checklists, markdown, reminders, colors, pinning, and labels',
  },
  {
    icon: LockIcon,
    title: 'End-to-end security',
    text: 'Locked notes stay private and are never uploaded',
  },
] as const;

interface AuthScreenProps {
  mode: AuthMode;
  dismissible?: boolean;
}

export function AuthScreen({ mode, dismissible = true }: AuthScreenProps) {
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
    <div className="fixed inset-0 z-[60] flex min-h-dvh flex-col bg-[#09090B]">
      {/* Ambient glow */}
      <div className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute -left-32 -top-32 h-[28rem] w-[28rem] rounded-full bg-brand-primary/[0.015] blur-3xl" />
        <div className="absolute -bottom-32 -right-32 h-[24rem] w-[24rem] rounded-full bg-brand-primary/[0.01] blur-3xl" />
      </div>

      {/* Close button — modal mode only */}
      {dismissible && (
        <header className="relative z-10 flex items-center justify-end px-3 pt-safe md:px-5">
          <button type="button" onClick={closeAuthScreen}
            className="flex size-10 items-center justify-center rounded-full text-brand-muted/50 transition-colors hover:bg-white/5 hover:text-brand-primary"
            aria-label="Close">
            <CloseIcon size={20} />
          </button>
        </header>
      )}

      {/* Main layout: flex-col on mobile, side-by-side on md+ */}
      <div className={`relative z-10 mx-auto flex w-full max-w-5xl flex-1 flex-col items-center justify-center gap-6 px-4 pb-safe sm:px-6 md:flex-row md:gap-12 md:px-8 lg:gap-20 lg:px-10 ${!dismissible ? 'pt-8' : ''}`}>

        {/* ── Left: Brand ── */}
        <div className={`w-full max-w-sm flex-shrink-0 text-center transition-all duration-700 ease-out md:max-w-md md:text-left ${animateIn ? 'translate-y-0 opacity-100' : 'translate-y-6 opacity-0'}`}>
          <BrandMark size={dismissible ? 64 : 80} />
          <p className="mt-5 text-[26px] font-bold tracking-tight text-brand-primary sm:text-[28px]">
            {copy.title}
          </p>
          <p className="mt-2 max-w-xs text-[15px] leading-relaxed text-brand-muted/80 mx-auto md:mx-0">
            {copy.subtitle}
          </p>
        </div>

        {/* ── Right: Sign-in card + features ── */}
        <div className={`w-full max-w-sm flex-shrink-0 transition-all duration-700 ease-out delay-150 md:max-w-[360px] ${animateIn ? 'translate-y-0 opacity-100' : 'translate-y-8 opacity-0'}`}>
          {/* Card */}
          <div className="rounded-2xl border border-white/[0.06] bg-white/[0.025] p-5 backdrop-blur-sm sm:p-6 md:rounded-3xl md:p-7">
            {/* Tab toggle */}
            <div className="rounded-xl border border-white/[0.06] bg-white/[0.03] p-1">
              <div className="grid grid-cols-2 gap-1">
                {(['signin', 'signup'] as const).map((tab) => {
                  const active = mode === tab;
                  return (
                    <button key={tab} type="button" onClick={() => openAuthScreen(tab)}
                      className={`rounded-lg px-3 py-2.5 text-[13px] font-semibold transition-all sm:text-sm ${active ? 'bg-white/10 text-brand-primary shadow-sm' : 'text-brand-muted/50 hover:text-brand-primary/80'}`}>
                      {tab === 'signin' ? 'Sign in' : 'Sign up'}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Google button + error */}
            <div className="mt-5 space-y-4 sm:mt-6">
              <GoogleSignInButton
                label={copy.googleLabel}
                onClick={() => void handleGoogle()}
                loading={loading}
              />

              {error ? (
                <p className="rounded-xl border border-red-900/40 bg-red-950/25 px-4 py-3 text-center text-[13px] text-red-200 sm:text-sm">
                  {error}
                </p>
              ) : null}

              {dismissible && (
                <>
                  <div className="relative flex items-center gap-3">
                    <div className="h-px flex-1 bg-white/[0.06]" />
                    <span className="text-xs font-medium uppercase tracking-wider text-brand-muted/40">
                      or
                    </span>
                    <div className="h-px flex-1 bg-white/[0.06]" />
                  </div>
                  <button type="button" onClick={closeAuthScreen}
                    className="w-full rounded-xl border border-white/[0.08] px-4 py-3 text-sm font-semibold text-brand-muted transition-all hover:bg-white/[0.03] hover:text-brand-primary active:scale-[0.98]">
                    Continue without an account
                  </button>
                </>
              )}
            </div>
          </div>

          {/* Feature tiles — below card on all screen sizes */}
          <div className="mt-5 space-y-2.5">
            {FEATURES.map(({ icon: Icon, title, text }) => (
              <div key={title} className="flex items-start gap-3 rounded-xl border border-white/[0.03] bg-white/[0.015] px-3.5 py-3 transition-colors hover:bg-white/[0.03]">
                <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-brand-primary/8 text-brand-primary">
                  <Icon size={16} />
                </span>
                <div className="min-w-0">
                  <p className="text-[13px] font-semibold text-brand-primary">{title}</p>
                  <p className="mt-0.5 text-[11px] leading-relaxed text-brand-muted/60">{text}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

      </div>
    </div>
  );
}
