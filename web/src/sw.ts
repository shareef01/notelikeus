/// <reference lib="webworker" />
import { cleanupOutdatedCaches, precacheAndRoute } from 'workbox-precaching';
import { registerRoute, NavigationRoute } from 'workbox-routing';
import { createHandlerBoundToURL } from 'workbox-precaching';

declare let self: ServiceWorkerGlobalScope;

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

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

async function loadReminders(): Promise<SwReminder[]> {
  try {
    const cache = await caches.open(REMINDER_CACHE);
    const stored = await cache.match(REMINDER_KEY);
    if (!stored) return [];
    const parsed: unknown = await stored.json();
    return Array.isArray(parsed) ? (parsed as SwReminder[]) : [];
  } catch {
    return [];
  }
}

async function saveReminders(reminders: SwReminder[]): Promise<void> {
  try {
    const cache = await caches.open(REMINDER_CACHE);
    await cache.put(
      REMINDER_KEY,
      new Response(JSON.stringify(reminders), {
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  } catch {
    // Storage unavailable — in-memory timers still cover this worker's lifetime.
  }
}

function cancelSwReminder(noteId: string) {
  const timerId = swTimers.get(noteId);
  if (timerId != null) {
    clearTimeout(timerId);
    swTimers.delete(noteId);
  }
}

function fireReminder(reminder: SwReminder): Promise<void> {
  return self.registration.showNotification(reminder.title || 'Reminder', {
    body: reminder.body || 'You have a note reminder',
    icon: '/icons/icon-192.png',
    // Same tag per note, so a catch-up that races an armed timer replaces rather than stacks.
    tag: `notelikeus-reminder-${reminder.noteId}`,
    data: { noteId: reminder.noteId },
  });
}

/** setTimeout delays above ~24.8 days clamp; re-arm until fireAt is actually due. */
const MAX_TIMER_DELAY_MS = 2_147_483_647;

function armTimer(reminder: SwReminder) {
  cancelSwReminder(reminder.noteId);
  const delay = reminder.fireAt - Date.now();
  if (delay <= 0) return;

  const timerId = setTimeout(() => {
    swTimers.delete(reminder.noteId);
    void (async () => {
      if (reminder.fireAt > Date.now()) {
        armTimer(reminder);
        return;
      }
      // Same reasoning as catchUpReminders: a rejected notification must not skip the cleanup
      // below, or the reminder stays stored and re-fires on every subsequent wake-up.
      try {
        await fireReminder(reminder);
      } catch {
        // Best-effort delivery.
      }
      // Drop it from storage so a later catch-up does not fire it a second time.
      const remaining = (await loadReminders()).filter((r) => r.noteId !== reminder.noteId);
      await saveReminders(remaining);
    })();
  }, Math.min(delay, MAX_TIMER_DELAY_MS));

  swTimers.set(reminder.noteId, timerId);
}

/** Fires anything already due and re-arms the rest. Safe to call on any worker wake-up. */
async function catchUpReminders(): Promise<void> {
  const stored = await loadReminders();
  if (stored.length === 0) return;

  const now = Date.now();
  const due = stored.filter((reminder) => reminder.fireAt <= now);
  const upcoming = stored.filter((reminder) => reminder.fireAt > now);

  for (const reminder of due) {
    // showNotification rejects when permission has been revoked since the reminder was set.
    // Letting that escape used to strand the whole batch: the remaining due reminders never
    // fired, and the save below never ran, so every one of them stayed stored and retried on
    // each wake-up. Dropping a reminder we cannot show is the better failure — the condition is
    // persistent, so retrying forever only produces repeated failures the user never sees.
    try {
      await fireReminder(reminder);
    } catch {
      // Best-effort delivery; it is removed from storage along with the rest of `due`.
    }
  }
  if (due.length > 0) await saveReminders(upcoming);
  for (const reminder of upcoming) armTimer(reminder);
}

async function syncSwReminders(reminders: SwReminder[]): Promise<void> {
  const activeIds = new Set(reminders.map((reminder) => reminder.noteId));
  // Snapshot the keys: cancelSwReminder mutates swTimers inside the loop.
  // oxlint-disable-next-line unicorn/no-useless-spread
  for (const noteId of [...swTimers.keys()]) {
    if (!activeIds.has(noteId)) cancelSwReminder(noteId);
  }
  await saveReminders(reminders);
  for (const reminder of reminders) armTimer(reminder);
}

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
    event.waitUntil(syncSwReminders(data.reminders));
  }
});

// Any navigation is a chance to deliver something the worker slept through. Throttled so a
// burst of requests does not re-read storage repeatedly.
let lastCatchUp = 0;
self.addEventListener('fetch', (event) => {
  if (event.request.mode !== 'navigate') return;
  const now = Date.now();
  if (now - lastCatchUp < 30_000) return;
  lastCatchUp = now;
  event.waitUntil(catchUpReminders());
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
      await catchUpReminders();
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
          client.postMessage({ type: 'OPEN_NOTE', noteId });
          return undefined;
        }
      }
      return self.clients.openWindow(targetUrl);
    })(),
  );
});
