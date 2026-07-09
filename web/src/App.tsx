import { useAuthSync } from '@/hooks/useAuth';
import { useNotesSync } from '@/hooks/useNotesSync';
import { isFirebaseConfigured } from '@/lib/config';
import { MainScreen } from '@/screens/MainScreen';
import { ThemeApplier } from '@/components/theme/ThemeApplier';
import { useUiStore } from '@/store/uiStore';
import { lazy, Suspense, useEffect } from 'react';

const EditorScreen = lazy(() =>
  import('@/screens/EditorScreen').then((module) => ({ default: module.EditorScreen })),
);
const AuthScreen = lazy(() =>
  import('@/screens/AuthScreen').then((module) => ({ default: module.AuthScreen })),
);

const firebaseReady = isFirebaseConfigured();

export default function App() {
  const editorMode = useUiStore((s) => s.editorRoute.mode);
  const editorNoteId = useUiStore((s) =>
    s.editorRoute.mode === 'edit' ? s.editorRoute.noteId : null,
  );
  const authScreen = useUiStore((s) => s.authScreen);
  const openNewNote = useUiStore((s) => s.openNewNote);

  useAuthSync();
  useNotesSync(firebaseReady);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('new') === '1') {
      openNewNote();
      window.history.replaceState({}, '', window.location.pathname);
    }
  }, [openNewNote]);

  if (!firebaseReady) {
    return (
      <div className="flex min-h-full items-center justify-center bg-true-black p-6">
        <p className="max-w-md rounded-note border border-amber-900/50 bg-amber-950/30 px-4 py-3 text-center text-sm text-amber-200">
          Copy web/.env.example to web/.env and set VITE_FIREBASE_APP_ID from Firebase Console.
        </p>
      </div>
    );
  }

  return (
    <>
      <ThemeApplier />
      <MainScreen />
      {editorMode === 'new' ? (
        <Suspense fallback={null}>
          <EditorScreen route={{ mode: 'new' }} />
        </Suspense>
      ) : editorMode === 'edit' && editorNoteId ? (
        <Suspense fallback={null}>
          <EditorScreen route={{ mode: 'edit', noteId: editorNoteId }} />
        </Suspense>
      ) : null}
      {authScreen ? (
        <Suspense fallback={null}>
          <AuthScreen mode={authScreen} />
        </Suspense>
      ) : null}
    </>
  );
}
