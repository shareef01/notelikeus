import type { Note } from '@/types/note';

interface SwReminder {
  noteId: string;
  title: string;
  body: string;
  fireAt: number;
}

async function postRemindersToServiceWorker(reminders: SwReminder[]) {
  if (!('serviceWorker' in navigator)) return;
  try {
    const registration = await navigator.serviceWorker.ready;
    registration.active?.postMessage({ type: 'SYNC_REMINDERS', reminders });
  } catch {
    // Service worker may be unavailable in some contexts.
  }
}

export function notesEligibleForReminders(notes: Note[]): Note[] {
  const now = Date.now();
  return notes
    .filter(
      (note) =>
        note.reminderTimestamp != null &&
        !note.isTrashed &&
        !note.isLocked &&
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
 * The service worker is the single owner of reminder delivery (`sw.ts`'s
 * `showNotification` call) — it can fire even when no tab is open, which a
 * page-context timer never could. This just keeps it in sync with current
 * note state; it does not itself schedule or show any notification.
 */
export function rescheduleAllReminders(notes: Note[]) {
  void postRemindersToServiceWorker(buildSwReminders(notes));
}
