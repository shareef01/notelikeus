import { useAuthListener, useAuthSync } from '@/hooks/useAuth';
import { useNotesSync } from '@/hooks/useNotesSync';
import { isFirebaseConfigured } from '@/lib/config';
import { MainScreen } from '@/screens/MainScreen';
import { ThemeApplier } from '@/components/theme/ThemeApplier';
import { useUiStore } from '@/store/uiStore';
import { useIsTabletUp } from '@/hooks/useMediaQuery';
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
  const authScreen = useUiStore((s) => s.authScreen);
  const labelsOpen = useUiStore((s) => s.labelsOpen);
  const setLabelsOpen = useUiStore((s) => s.setLabelsOpen);
  const openNewNote = useUiStore((s) => s.openNewNote);
  const { user, isReady: authReady } = useAuthListener();
  const isTabletUp = useIsTabletUp();
  const [authTimedOut, setAuthTimedOut] = useState(false);

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

  if (!firebaseReady) {
    return (
      <div className="flex min-h-full items-center justify-center bg-true-surface p-6">
        <p className="max-w-md rounded-note border border-amber-900/50 bg-amber-950/30 px-4 py-3 text-center text-sm text-amber-200">
          Copy web/.env.example to web/.env and set VITE_FIREBASE_APP_ID from Firebase Console.
        </p>
      </div>
    );
  }

  if (!authReady) {
    return (
      <div className="flex min-h-full flex-col items-center justify-center gap-4 bg-true-surface px-6 text-center">
        <div className="size-8 animate-pulse rounded-full bg-brand-outline/60" aria-hidden />
        <p className="text-sm text-brand-muted">Checking your sign-in status…</p>
        {authTimedOut ? (
          <>
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
          </>
        ) : null}
      </div>
    );
  }

  if (!user) {
    return (
      <Suspense fallback={null}>
        <AuthScreen mode="signin" mandatory />
      </Suspense>
    );
  }

  const showMobileEditor = !isTabletUp && editorMode !== 'closed';

  return (
    <>
      <ThemeApplier />

      <Suspense fallback={null}>
        <Routes>
          <Route path="/" element={<MainScreen />} />
          <Route path="/note/new" element={<EditorScreen mode="new" />} />
          <Route path="/note/:id" element={<EditorScreen mode="edit" />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>

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
