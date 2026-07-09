import { createRoot } from 'react-dom/client';
import { BootGate } from '@/components/boot/BootGate';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
import './styles/globals.css';

const root = document.getElementById('root')!;

createRoot(root).render(
  <ErrorBoundary>
    <BootGate />
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
      onRegisteredSW(_url, registration) {
        if (registration?.waiting) {
          registration.waiting.postMessage({ type: 'SKIP_WAITING' });
        }
      },
    });
  } catch (error) {
    console.warn('[PWA] Service worker registration failed:', error);
  }
})();
