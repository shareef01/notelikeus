import type { Note } from '@/types/note';

const timers = new Map<string, number>();

export async function requestNotificationPermission(): Promise<boolean> {
  if (!('Notification' in window)) return false;
  if (Notification.permission === 'granted') return true;
  if (Notification.permission === 'denied') return false;
  const result = await Notification.requestPermission();
  return result === 'granted';
}

function showReminderNotification(title: string, body: string) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  const label = title.trim() || 'Note reminder';
  try {
    new Notification(label, {
      body: body.trim() || 'You have a note reminder',
      icon: '/icons/icon-192.png',
      tag: `notelikeus-reminder-${Date.now()}`,
    });
  } catch {
    // Some browsers block notifications outside secure contexts.
  }
}

export function cancelNoteReminder(noteId: string) {
  const timerId = timers.get(noteId);
  if (timerId != null) {
    window.clearTimeout(timerId);
    timers.delete(noteId);
  }
}

export function scheduleNoteReminder(note: Pick<Note, 'id' | 'title' | 'content' | 'reminderTimestamp'>) {
  cancelNoteReminder(note.id);
  if (note.reminderTimestamp == null) return;

  const delay = note.reminderTimestamp - Date.now();
  if (delay <= 0) return;

  const timerId = window.setTimeout(() => {
    timers.delete(note.id);
    showReminderNotification(note.title, note.content);
  }, Math.min(delay, 2_147_483_647));

  timers.set(note.id, timerId);
}

export function rescheduleAllReminders(notes: Note[]) {
  for (const noteId of timers.keys()) {
    if (!notes.some((note) => note.id === noteId && note.reminderTimestamp != null)) {
      cancelNoteReminder(noteId);
    }
  }
  for (const note of notes) {
    if (note.reminderTimestamp != null && !note.isTrashed) {
      scheduleNoteReminder(note);
    }
  }
}
