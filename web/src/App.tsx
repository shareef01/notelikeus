import { useAuthSync, useAuthListener } from '@/hooks/useAuth';
import { useNotesSync } from '@/hooks/useNotesSync';
import { isFirebaseConfigured } from '@/lib/config';
import { MainScreen } from '@/screens/MainScreen';
import { ThemeApplier } from '@/components/theme/ThemeApplier';
import { useUiStore } from '@/store/uiStore';
import { lazy, Suspense } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';

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

  useAuthSync();
  useNotesSync(firebaseReady);

  const { user, isReady } = useAuthListener();
  const isAuthenticated = isReady && user != null;

  if (!firebaseReady) {
    return (
      <div className="flex min-h-full items-center justify-center bg-true-black p-6 text-center">
        <p className="max-w-md rounded-2xl border border-amber-900/50 bg-amber-950/30 px-6 py-4 text-sm text-amber-200 shadow-2xl">
          Missing Firebase Configuration.
          <br />
          <span className="mt-2 block opacity-70">Check web/.env and VITE_FIREBASE_APP_ID.</span>
        </p>
      </div>
    );
  }

  // Show loading spinner while Firebase Auth initializes
  if (!isReady) {
    return (
      <div className="flex min-h-full items-center justify-center bg-true-black">
        <div className="size-8 animate-spin rounded-full border-2 border-white/20 border-t-white/80" />
      </div>
    );
  }

  // Not signed in — show the auth gate
  if (!isAuthenticated) {
    return (
      <>
        <ThemeApplier />
        <Suspense fallback={null}>
          <AuthScreen mode={authScreen || 'signin'} dismissible={false} />
        </Suspense>
      </>
    );
  }

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
