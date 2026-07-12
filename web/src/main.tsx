import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { BootGate } from '@/components/boot/BootGate';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
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
    registerSW({
      immediate: true,
      onOfflineReady() {
        console.info('[PWA] App ready to work offline.');
      },
      onNeedRefresh() {
        window.location.reload();
      },
      onRegisteredSW(_url, registration) {
        if (!registration) return;

        if (registration.waiting) {
          registration.waiting.postMessage({ type: 'SKIP_WAITING' });
        }

        registration.addEventListener('updatefound', () => {
          const installing = registration.installing;
          if (!installing) return;

          installing.addEventListener('statechange', () => {
            if (installing.state === 'installed' && navigator.serviceWorker.controller) {
              installing.postMessage({ type: 'SKIP_WAITING' });
            }
          });
        });
      },
    });

    navigator.serviceWorker?.addEventListener('controllerchange', () => {
      window.location.reload();
    });
  } catch (error) {
    console.warn('[PWA] Service worker registration failed:', error);
  }
})();
