import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { BootGate } from '@/components/boot/BootGate';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import './styles/globals.css';

const root = document.getElementById('root')!;

createRoot(root).render(
  <ErrorBoundary>
    <BrowserRouter>
      <BootGate />
    </BrowserRouter>
  </ErrorBoundary>,
);

void (async () => {
  try {
    const { registerSW } = await import('virtual:pwa-register');
    // Auto-reload when a new SW is waiting. The "Reload" toast only mounts inside
    // the signed-in shell — on the login gate users would otherwise stay stuck on
    // a precached AuthScreen forever and never see new UI (e.g. test login).
    const updateSW = registerSW({
      immediate: true,
      onNeedRefresh() {
        void updateSW(true);
      },
      onOfflineReady() {
        console.info('[PWA] App ready to work offline.');
      },
    });

    navigator.serviceWorker?.addEventListener('controllerchange', () => {
      window.location.reload();
    });
  } catch (error) {
    console.warn('[PWA] Service worker registration failed:', error);
  }
})();
