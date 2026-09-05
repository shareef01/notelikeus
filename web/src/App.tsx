import { useAuthListener, useAuthSync } from '@/hooks/useAuth';
import { useGuestLocalNotesBootstrap } from '@/hooks/useGuestLocalNotesBootstrap';
import { useNotesSync } from '@/hooks/useNotesSync';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';
import { MainScreen } from '@/screens/MainScreen';
import { ThemeApplier } from '@/components/theme/ThemeApplier';
import { AppSplash } from '@/components/boot/AppSplash';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
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

const remoteReady = isSupabaseBackendEnabled();

export default function App() {
  const editorMode = useUiStore((s) => s.editorRoute.mode);
  const editorNoteId = useUiStore((s) =>
    s.editorRoute.mode === 'edit' ? s.editorRoute.noteId : null,
  );
  const authScreen = useUiStore((s) => s.authScreen);
  const labelsOpen = useUiStore((s) => s.labelsOpen);
  const setLabelsOpen = useUiStore((s) => s.setLabelsOpen);
  const openNewNote = useUiStore((s) => s.openNewNote);
  const openNote = useUiStore((s) => s.openNote);
  const closeEditor = useUiStore((s) => s.closeEditor);
  const closeAuthScreen = useUiStore((s) => s.closeAuthScreen);
  const { user, isReady: authReady, isGuest } = useAuthListener();
  const isTabletUp = useIsTabletUp();
  const [authTimedOut, setAuthTimedOut] = useState(false);
  const [hadSession] = useState(hadSessionLastLoad);

  const assumeSignedIn = !authReady && hadSession;

  useAuthSync();
  useNotesSync(remoteReady);
  useGuestLocalNotesBootstrap(remoteReady);

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
      return;
    }
    const noteId = params.get('note');
    if (noteId && /^\d+$/.test(noteId)) {
      openNote(noteId);
      window.history.replaceState({}, '', window.location.pathname);
    }
  }, [openNewNote, openNote]);

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      const data = event.data as { type?: string; noteId?: string } | null;
      if (data?.type === 'OPEN_NOTE' && typeof data.noteId === 'string' && data.noteId) {
        openNote(data.noteId);
      }
    };
    navigator.serviceWorker?.addEventListener('message', onMessage);
    return () => navigator.serviceWorker?.removeEventListener('message', onMessage);
  }, [openNote]);

  const themeApplier = <ThemeApplier />;

  if (!remoteReady) {
    return (
      <div className="flex min-h-full items-center justify-center bg-true-surface p-6">
        {themeApplier}
        <p className="max-w-md rounded-note border border-amber-900/50 bg-amber-950/30 px-4 py-3 text-center text-sm text-amber-200">
          Copy web/.env.example to web/.env and set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY.
        </p>
      </div>
    );
  }

  if (!isGuest && !authReady && !hadSession) {
    return (
      <>
        {themeApplier}
        <AppSplash />
        {authTimedOut ? (
          <div className="fixed inset-x-0 bottom-16 flex flex-col items-center gap-3 px-6 text-center">
            <p className="max-w-xs text-xs text-brand-muted">
              This is taking longer than expected. It's safe to reload — nothing has been changed
              yet.
            </p>
            <button
              type="button"
              onClick={() => window.location.reload()}
              className="rounded-note border border-brand-outline/50 px-4 py-2 text-sm font-semibold text-brand-primary transition-colors hover:bg-brand-primary/5"
            >
              Reload
            </button>
          </div>
        ) : null}
      </>
    );
  }

  if (!user && !isGuest && !assumeSignedIn) {
    return (
      <>
        {themeApplier}
        <ErrorBoundary allowClearData={false}>
          <Suspense fallback={<AppSplash />}>
            <AuthScreen mode="signin" mandatory />
          </Suspense>
        </ErrorBoundary>
      </>
    );
  }

  const showMobileEditor = !isTabletUp && editorMode !== 'closed';

  return (
    <>
      {themeApplier}
      <MainScreen />
      {showMobileEditor ? (
        <ErrorBoundary
          variant="overlay"
          allowClearData={false}
          onDismiss={closeEditor}
        >
          <Suspense fallback={null}>
            {editorMode === 'new' ? (
              <EditorScreen key="new" route={{ mode: 'new' }} />
            ) : editorNoteId ? (
              <EditorScreen key={editorNoteId} route={{ mode: 'edit', noteId: editorNoteId }} />
            ) : null}
          </Suspense>
        </ErrorBoundary>
      ) : null}
      {authScreen ? (
        <ErrorBoundary
          variant="overlay"
          allowClearData={false}
          onDismiss={closeAuthScreen}
        >
          <Suspense fallback={null}>
            <AuthScreen mode={authScreen} />
          </Suspense>
        </ErrorBoundary>
      ) : null}
      {labelsOpen ? (
        <ErrorBoundary
          variant="overlay"
          allowClearData={false}
          onDismiss={() => setLabelsOpen(false)}
        >
          <Suspense fallback={null}>
            <LabelsScreen onClose={() => setLabelsOpen(false)} />
          </Suspense>
        </ErrorBoundary>
      ) : null}
    </>
  );
}
