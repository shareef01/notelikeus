import { createRoot } from 'react-dom/client';
import { BootGate } from '@/components/boot/BootGate';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import './styles/globals.css';
import { whenEditorSavesSettled } from '@/lib/notes/pendingEditorSaves';
import { useNotesStore } from '@/store/notesStore';
import {
  getCachedThumbnailCount,
  getTotalActiveThumbnailRefCount,
} from '@/lib/attachments/attachmentThumbnailCache';
import { storePendingAttachment } from '@/lib/attachments/pendingAttachmentStore';

if (import.meta.env.MODE === 'e2e' || import.meta.env.DEV) {
  (window as unknown as { __NOTELIKEUS_NOTES_STORE__?: typeof useNotesStore }).__NOTELIKEUS_NOTES_STORE__ = useNotesStore;
  (window as unknown as {
    __NOTELIKEUS_THUMBNAIL_METRICS__?: {
      getCachedThumbnailCount: typeof getCachedThumbnailCount;
      getTotalActiveThumbnailRefCount: typeof getTotalActiveThumbnailRefCount;
      storePendingAttachment: typeof storePendingAttachment;
    };
  }).__NOTELIKEUS_THUMBNAIL_METRICS__ = {
    getCachedThumbnailCount,
    getTotalActiveThumbnailRefCount,
    storePendingAttachment,
  };
}

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
