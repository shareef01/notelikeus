import { useAuthSync } from '@/hooks/useAuth';
import { useNotesSync } from '@/hooks/useNotesSync';
import { isFirebaseConfigured } from '@/lib/config';
import { AuthScreen } from '@/screens/AuthScreen';
import { EditorScreen } from '@/screens/EditorScreen';
import { MainScreen } from '@/screens/MainScreen';
import { ThemeApplier } from '@/components/theme/ThemeApplier';
import { useUiStore } from '@/store/uiStore';

const firebaseReady = isFirebaseConfigured();

export default function App() {
  const editorMode = useUiStore((s) => s.editorRoute.mode);
  const editorNoteId = useUiStore((s) =>
    s.editorRoute.mode === 'edit' ? s.editorRoute.noteId : null,
  );
  const authScreen = useUiStore((s) => s.authScreen);

  useAuthSync();
  useNotesSync(firebaseReady);

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
        <EditorScreen route={{ mode: 'new' }} />
      ) : editorMode === 'edit' && editorNoteId ? (
        <EditorScreen route={{ mode: 'edit', noteId: editorNoteId }} />
      ) : null}
      {authScreen ? <AuthScreen mode={authScreen} /> : null}
    </>
  );
}
