/// <reference lib="webworker" />
import { cleanupOutdatedCaches, precacheAndRoute } from 'workbox-precaching';
import { registerRoute, NavigationRoute } from 'workbox-routing';
import { CacheFirst } from 'workbox-strategies';
import { ExpirationPlugin } from 'workbox-expiration';
import { createHandlerBoundToURL } from 'workbox-precaching';

declare let self: ServiceWorkerGlobalScope;

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

self.addEventListener('install', () => {
  void self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

const handler = createHandlerBoundToURL('/index.html');
const navigationRoute = new NavigationRoute(handler, {
  denylist: [/^\/_/, /\/[^/?]+\.[^/]+$/],
});
registerRoute(navigationRoute);

registerRoute(
  /^https:\/\/fonts\.googleapis\.com\/.*/i,
  new CacheFirst({
    cacheName: 'google-fonts-stylesheets',
    plugins: [new ExpirationPlugin({ maxEntries: 10, maxAgeSeconds: 60 * 60 * 24 * 365 })],
  }),
);

registerRoute(
  /^https:\/\/fonts\.gstatic\.com\/.*/i,
  new CacheFirst({
    cacheName: 'google-fonts-webfonts',
    plugins: [new ExpirationPlugin({ maxEntries: 20, maxAgeSeconds: 60 * 60 * 24 * 365 })],
  }),
);

// Firestore has its own IndexedDB offline persistence — do NOT
// cache API responses in the service worker. Cached error/403
// responses can permanently break sync until the cache expires.


interface SwReminder {
  noteId: string;
  title: string;
  body: string;
  fireAt: number;
}

const swTimers = new Map<string, ReturnType<typeof setTimeout>>();

function cancelSwReminder(noteId: string) {
  const timerId = swTimers.get(noteId);
  if (timerId != null) {
    clearTimeout(timerId);
    swTimers.delete(noteId);
  }
}

function scheduleSwReminder(reminder: SwReminder) {
  cancelSwReminder(reminder.noteId);
  const delay = reminder.fireAt - Date.now();
  if (delay <= 0) return;

  const timerId = setTimeout(() => {
    swTimers.delete(reminder.noteId);
    void self.registration.showNotification(reminder.title || 'Note reminder', {
      body: reminder.body || 'You have a note reminder',
      icon: '/icons/icon-192.png',
      tag: `notelikeus-reminder-${reminder.noteId}`,
    });
  }, Math.min(delay, 2_147_483_647));

  swTimers.set(reminder.noteId, timerId);
}

function syncSwReminders(reminders: SwReminder[]) {
  const activeIds = new Set<string>();
  for (const reminder of reminders) {
    activeIds.add(reminder.noteId);
    scheduleSwReminder(reminder);
  }
  for (const noteId of swTimers.keys()) {
    if (!activeIds.has(noteId)) {
      cancelSwReminder(noteId);
    }
  }
}

self.addEventListener('message', (event) => {
  const data = event.data as { type?: string; reminders?: SwReminder[] } | null;
  if (data?.type === 'SKIP_WAITING') {
    void self.skipWaiting();
    return;
  }
  if (data?.type === 'SYNC_REMINDERS' && Array.isArray(data.reminders)) {
    syncSwReminders(data.reminders);
  }
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if ('focus' in client) {
          return client.focus();
        }
      }
      return self.clients.openWindow('/');
    }),
  );
});
