export interface SwReminder {
  noteId: string;
  title: string;
  body: string;
  fireAt: number;
}

/** Matches the note id shape the app produces (`String(localId)`) and the `?note=` deep-link guard. */
const NOTE_ID_PATTERN = /^\d+$/;

/** Nothing legitimate schedules more than this; the cap bounds timers and the cached payload. */
export const MAX_SW_REMINDERS = 1_000;

/**
 * Keeps only well-formed reminders.
 *
 * The service worker takes reminders from two places — a `SYNC_REMINDERS` message from a window
 * client, and its own cache — and both were cast straight to `SwReminder[]` without a shape check.
 * Neither source is reachable cross-origin, but a payload written by an older schema, a
 * half-written cache entry, or anything arriving after an XSS then flowed into the timer and
 * `showNotification` paths, where one malformed entry throws and takes every other reminder for
 * that user down with it, silently and until the next full resync.
 *
 * `title` and `body` are deliberately not copied from the input: reminder notifications must never
 * carry note text onto a lock screen, so the worker owns those strings outright.
 */
export function sanitizeReminders(value: unknown): SwReminder[] {
  if (!Array.isArray(value)) return [];
  const clean: SwReminder[] = [];
  for (const entry of value) {
    if (clean.length >= MAX_SW_REMINDERS) break;
    if (!entry || typeof entry !== 'object') continue;
    const candidate = entry as Partial<SwReminder>;
    if (typeof candidate.noteId !== 'string' || !NOTE_ID_PATTERN.test(candidate.noteId)) continue;
    if (typeof candidate.fireAt !== 'number' || !Number.isFinite(candidate.fireAt)) continue;
    clean.push({
      noteId: candidate.noteId,
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: candidate.fireAt,
    });
  }
  return clean;
}
