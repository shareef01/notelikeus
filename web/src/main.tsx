import { createRoot } from 'react-dom/client';
import { BootGate } from '@/components/boot/BootGate';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import './styles/globals.css';
import { whenEditorSavesSettled } from '@/lib/notes/pendingEditorSaves';

const root = document.getElementById('root')!;

createRoot(root).render(
  <ErrorBoundary>
    <BootGate />
  </ErrorBoundary>,
);

void (async () => {
  try {
    const { registerSW } = await import('virtual:pwa-register');
    // Auto-reload when a new SW is waiting. The "Reload" toast only mounts inside
    // the signed-in shell — on the login gate users would otherwise stay stuck on
    // a precached AuthScreen forever and never see new UI (e.g. test login).
    // The reload waits until no open editor holds an edit that has not reached IndexedDB: the
    // editor's own flush on pagehide is async, and a reload does not wait for it.
    const updateSW = registerSW({
      immediate: true,
      onNeedRefresh() {
        void whenEditorSavesSettled().then(() => updateSW(true));
      },
      onOfflineReady() {
        console.info('[PWA] App ready to work offline.');
      },
    });
  } catch (error) {
    console.warn('[PWA] Service worker registration failed:', error);
  }
})();
