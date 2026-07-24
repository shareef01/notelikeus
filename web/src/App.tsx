import { useAuthListener, useAuthSync } from '@/hooks/useAuth';
import { useNotesSync } from '@/hooks/useNotesSync';
import { isFirebaseConfigured } from '@/lib/config';
import { MainScreen } from '@/screens/MainScreen';
import { ThemeApplier } from '@/components/theme/ThemeApplier';
import { AppSplash } from '@/components/boot/AppSplash';
import { useUiStore } from '@/store/uiStore';
import { useIsTabletUp } from '@/hooks/useMediaQuery';
import { hadSessionLastLoad } from '@/lib/auth/sessionHint';
import { lazy, Suspense, useEffect, useState } from 'react';

const AUTH_READY_TIMEOUT_MS = 8_000;

const EditorScreen = lazy(() =>
  import('@/screens/EditorScreen').then((module) => ({ default: module.EditorScreen })),
);
const AuthScreen = lazy(() =>
  import('@/screens/AuthScreen').then((module) => ({ default: module.AuthScreen })),
);
const LabelsScreen = lazy(() =>
  import('@/screens/LabelsScreen').then((module) => ({ default: module.LabelsScreen })),
);

const firebaseReady = isFirebaseConfigured();

export default function App() {
  const editorMode = useUiStore((s) => s.editorRoute.mode);
  const editorNoteId = useUiStore((s) =>
    s.editorRoute.mode === 'edit' ? s.editorRoute.noteId : null,
  );
  const authScreen = useUiStore((s) => s.authScreen);
  const labelsOpen = useUiStore((s) => s.labelsOpen);
  const setLabelsOpen = useUiStore((s) => s.setLabelsOpen);
  const openNewNote = useUiStore((s) => s.openNewNote);
  const { user, isReady: authReady } = useAuthListener();
  const isTabletUp = useIsTabletUp();
  const [authTimedOut, setAuthTimedOut] = useState(false);
  // Read once, so it cannot flip mid-render as auth resolves.
  const [hadSession] = useState(hadSessionLastLoad);

  // Firebase restores its session asynchronously, so authReady is false for a moment on every
  // load. Notes are already rehydrated from local storage by this point, so someone who was
  // signed in last time gets the app immediately instead of a full-screen "checking sign-in"
  // gate on every refresh. If auth then resolves to no user, the sign-in screen takes over.
  const assumeSignedIn = !authReady && hadSession;

  useAuthSync();
  useNotesSync(firebaseReady);

  useEffect(() => {
    if (authReady) {
      setAuthTimedOut(false);
      return;
    }
    const timer = window.setTimeout(() => setAuthTimedOut(true), AUTH_READY_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [authReady]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('new') === '1') {
      openNewNote();
      window.history.replaceState({}, '', window.location.pathname);
    }
  }, [openNewNote]);

  // Mounted above every early return below: it renders nothing and only sets the theme class on
  // <html>. Sitting under the `!user` return meant the loading and sign-in screens — the first
  // thing anyone sees — ran with no theme class, falling back to :root's light palette on the
  // shell's dark background.
  const themeApplier = <ThemeApplier />;

  if (!firebaseReady) {
    return (
      <div className="flex min-h-full items-center justify-center bg-true-surface p-6">
        {themeApplier}
        <p className="max-w-md rounded-note border border-amber-900/50 bg-amber-950/30 px-4 py-3 text-center text-sm text-amber-200">
          Copy web/.env.example to web/.env and set VITE_FIREBASE_APP_ID from Firebase Console.
        </p>
      </div>
    );
  }

  if (!authReady && !hadSession) {
    // Same splash the boot screen shows, so a first visit reads as one continuous "Loading…"
    // rather than a third differently-styled screen. The timeout affordance only appears if
    // auth genuinely stalls.
    return (
      <>
        {themeApplier}
        <AppSplash />
        {authTimedOut ? (
          <div className="fixed inset-x-0 bottom-16 flex flex-col items-center gap-3 px-6 text-center">
            <p className="max-w-xs text-xs text-brand-muted">
              This is taking longer than expected. Your notes on this device are safe either way.
            </p>
            <button
              type="button"
              onClick={() => window.location.reload()}
              className="rounded-note border border-brand-outline/50 px-4 py-2 text-sm font-semibold text-brand-primary transition-colors hover:bg-white/5"
            >
              Reload
            </button>
          </div>
        ) : null}
      </>
    );
  }

  if (!user && !assumeSignedIn) {
    return (
      <>
        {themeApplier}
        {/* Splash, not null, while the AuthScreen chunk loads — otherwise the shell flashes
            blank on a first visit, and on an expired session flashes blank between the notes
            we optimistically showed and this gate. */}
        <Suspense fallback={<AppSplash />}>
          <AuthScreen mode="signin" mandatory />
        </Suspense>
      </>
    );
  }

  const showMobileEditor = !isTabletUp && editorMode !== 'closed';

  return (
    <>
      {themeApplier}
      <MainScreen />
      {showMobileEditor ? (
        <Suspense fallback={null}>
          {editorMode === 'new' ? (
            <EditorScreen route={{ mode: 'new' }} />
          ) : editorNoteId ? (
            <EditorScreen route={{ mode: 'edit', noteId: editorNoteId }} />
          ) : null}
        </Suspense>
      ) : null}
      {authScreen ? (
        <Suspense fallback={null}>
          <AuthScreen mode={authScreen} />
        </Suspense>
      ) : null}
      {labelsOpen ? (
        <Suspense fallback={null}>
          <LabelsScreen onClose={() => setLabelsOpen(false)} />
        </Suspense>
      ) : null}
    </>
  );
}
