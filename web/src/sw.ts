/// <reference lib="webworker" />
import { cleanupOutdatedCaches, precacheAndRoute } from 'workbox-precaching';
import { registerRoute, NavigationRoute } from 'workbox-routing';
import { NAVIGATION_DENYLIST } from '@/lib/hosting/navigationDenylist';
import { sanitizeReminders, type SwReminder } from '@/lib/reminders/swReminderPayload';
import { SwReminderManager, type SwReminderStorage } from '@/lib/reminders/swReminderManager';
import { createHandlerBoundToURL } from 'workbox-precaching';

declare let self: ServiceWorkerGlobalScope;

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

const FONT_CACHE = 'notelikeus-fonts';

async function cacheFirstFont(request: Request): Promise<Response> {
  const cache = await caches.open(FONT_CACHE);
  const cached = await cache.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok) {
    await cache.put(request, response.clone());
  }
  return response;
}

const handler = createHandlerBoundToURL('/index.html');
const navigationRoute = new NavigationRoute(handler, { denylist: NAVIGATION_DENYLIST });
registerRoute(navigationRoute);

/**
 * Timers do not survive the worker. The browser terminates an idle service worker within
 * seconds, taking every pending setTimeout with it, so an in-memory schedule alone means any
 * reminder further out than that is simply lost — there is no wake-up for a plain timer.
 *
 * Reminders are therefore persisted, and every event that happens to start the worker
 * (activate, a message, a navigation fetch) triggers a catch-up that fires anything already
 * due. Delivery is best-effort and can be late; without the Push API there is no way to
 * guarantee a service worker runs at an arbitrary future moment.
 */
const REMINDER_CACHE = 'notelikeus-reminders';
const REMINDER_KEY = '/__reminders';

const reminderStorage: SwReminderStorage = {
  async load(): Promise<SwReminder[]> {
    try {
      const cache = await caches.open(REMINDER_CACHE);
      const stored = await cache.match(REMINDER_KEY);
      if (!stored) return [];
      const parsed: unknown = await stored.json();
      return sanitizeReminders(parsed);
    } catch (error) {
      console.warn('[Notelikeus SW] Stored reminders unreadable; starting with none:', error);
      return [];
    }
  },

  async save(reminders: SwReminder[]): Promise<void> {
    try {
      const cache = await caches.open(REMINDER_CACHE);
      await cache.put(
        REMINDER_KEY,
        new Response(JSON.stringify(reminders), {
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    } catch (error) {
      // Storage unavailable — in-memory timers still cover this worker's lifetime, but reminders
      // will not survive it being terminated.
      console.warn('[Notelikeus SW] Could not persist reminders:', error);
    }
  },
};

function fireReminder(reminder: SwReminder): Promise<void> {
  return self.registration.showNotification(reminder.title || 'Reminder', {
    body: reminder.body || 'You have a note reminder',
    icon: '/icons/icon-192.png',
    // Same tag per note, so a catch-up that races an armed timer replaces rather than stacks.
    tag: `notelikeus-reminder-${reminder.noteId}`,
    data: { noteId: reminder.noteId },
  });
}

const reminderManager = new SwReminderManager({
  storage: reminderStorage,
  fireNotification: fireReminder,
});

self.addEventListener('message', (event) => {
  // Only accept control messages from same-origin window clients (not arbitrary workers).
  const source = event.source;
  if (!source || !('url' in source)) return;
  try {
    const clientUrl = new URL((source as Client).url);
    if (clientUrl.origin !== self.location.origin) return;
  } catch {
    return;
  }

  const data = event.data as { type?: string; reminders?: unknown[] } | null;
  if (data?.type === 'SKIP_WAITING') {
    // Sent by the client's updateSW(true) (see main.tsx's onNeedRefresh). With
    // injectManifest, Workbox does not inject this call for us — without it, a
    // waiting worker never activates until every tab for the origin is closed,
    // so the "Reload" toast wouldn't actually serve the new bundle.
    void self.skipWaiting();
    return;
  }
  if (data?.type === 'SYNC_REMINDERS' && Array.isArray(data.reminders)) {
    event.waitUntil(reminderManager.syncReminders(data.reminders));
  }
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.origin === self.location.origin && url.pathname.endsWith('.woff2')) {
    event.respondWith(cacheFirstFont(event.request));
    return;
  }
  if (event.request.mode !== 'navigate') return;
  event.waitUntil(reminderManager.catchUpThrottled());
});

self.addEventListener('activate', (event) => {
  // Drop legacy caches from older SW versions (Firestore API + remote Google Fonts).
  event.waitUntil(
    (async () => {
      await caches.delete('firestore-api');
      await caches.delete('google-fonts-stylesheets');
      await caches.delete('google-fonts-webfonts');
      await self.clients.claim();
      // Deliver anything that came due while no worker was running.
      await reminderManager.catchUpReminders();
    })(),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const noteId = (event.notification.data as { noteId?: string } | null)?.noteId;
  const targetUrl = noteId
    ? `/?note=${encodeURIComponent(noteId)}`
    : '/';
  event.waitUntil(
    (async () => {
      const clients = await self.clients.matchAll({
        type: 'window',
        includeUncontrolled: true,
      });
      for (const client of clients) {
        if ('focus' in client) {
          await client.focus();
          if ('navigate' in client) {
            return (client as WindowClient).navigate(targetUrl);
          }
          // False positive: the rule is written for `Window.postMessage(message, targetOrigin)`.
          // This is a ServiceWorker `Client`, whose signature is `postMessage(message, transfer?)`
          // — there is no targetOrigin to pass, and adding one would be a transfer list. The
          // message also never crosses an origin: `clients.matchAll` only ever returns clients this
          // service worker controls, which are same-origin by definition.
          // oxlint-disable-next-line unicorn/require-post-message-target-origin
          client.postMessage({ type: 'OPEN_NOTE', noteId });
          return undefined;
        }
      }
      return self.clients.openWindow(targetUrl);
    })(),
  );
});
