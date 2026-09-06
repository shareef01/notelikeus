import type { SwReminder } from '@/lib/reminders/swReminderPayload';
import type { Note } from '@/types/note';

async function postRemindersToServiceWorker(reminders: SwReminder[]) {
  if (!('serviceWorker' in navigator)) return;
  try {
    const registration = await navigator.serviceWorker.ready;
    registration.active?.postMessage({ type: 'SYNC_REMINDERS', reminders });
  } catch (error) {
    // Service worker may be unavailable in some contexts (private windows, unsupported
    // browsers), and reminders are best-effort by design — but a failure here means none of
    // this user's reminders are scheduled, so it must not vanish entirely.
    console.warn('[Notelikeus] Could not hand reminders to the service worker:', error);
  }
}

function buildSwReminders(notes: Note[]): SwReminder[] {
  const now = Date.now();
  return notes
    .filter(
      (note) =>
        note.reminderTimestamp != null &&
        !note.isTrashed &&
        note.reminderTimestamp > now,
    )
    .map((note) => ({
      noteId: note.id,
      // Never put note body/title into notification payloads (lock screen leakage).
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: note.reminderTimestamp!,
    }));
}

export async function requestNotificationPermission(): Promise<boolean> {
  if (!('Notification' in window)) return false;
  if (Notification.permission === 'granted') return true;
  if (Notification.permission === 'denied') return false;
  const result = await Notification.requestPermission();
  return result === 'granted';
}

/**
 * The service worker is the single owner of reminder delivery (`sw.ts`'s `showNotification`
 * call). This just keeps it in sync with current note state; it does not itself schedule or
 * show any notification.
 *
 * Delivery is best-effort and may be late. The worker is terminated when idle, so its timers
 * do not survive; `sw.ts` persists the schedule and fires anything overdue the next time the
 * worker runs for any reason. A reminder for a browser that is never reopened will not fire at
 * its appointed time — guaranteeing that needs the Push API and a server. Android's
 * AlarmManager path does not share this limitation.
 */
export function rescheduleAllReminders(notes: Note[]) {
  void postRemindersToServiceWorker(buildSwReminders(notes));
}
