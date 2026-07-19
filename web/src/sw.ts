/// <reference lib="webworker" />
import { cleanupOutdatedCaches, precacheAndRoute } from 'workbox-precaching';
import { registerRoute, NavigationRoute } from 'workbox-routing';
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
    void self.registration.showNotification(reminder.title || 'Reminder', {
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
  // Only accept control messages from same-origin window clients (not arbitrary workers).
  const source = event.source;
  if (source && 'url' in source) {
    try {
      const clientUrl = new URL((source as Client).url);
      if (clientUrl.origin !== self.location.origin) return;
    } catch {
      return;
    }
  }

  const data = event.data as { type?: string; reminders?: SwReminder[] } | null;
  if (data?.type === 'SKIP_WAITING') {
    // Sent by the client's updateSW(true) (see main.tsx's onNeedRefresh). With
    // injectManifest, Workbox does not inject this call for us — without it, a
    // waiting worker never activates until every tab for the origin is closed,
    // so the "Reload" toast wouldn't actually serve the new bundle.
    void self.skipWaiting();
    return;
  }
  if (data?.type === 'SYNC_REMINDERS' && Array.isArray(data.reminders)) {
    syncSwReminders(data.reminders);
  }
});

self.addEventListener('activate', (event) => {
  // Drop legacy caches from older SW versions (Firestore API + remote Google Fonts).
  event.waitUntil(
    (async () => {
      await caches.delete('firestore-api');
      await caches.delete('google-fonts-stylesheets');
      await caches.delete('google-fonts-webfonts');
      await self.clients.claim();
    })(),
  );
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
