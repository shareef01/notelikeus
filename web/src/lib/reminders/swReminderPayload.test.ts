import { describe, expect, it } from 'vitest';
import { MAX_SW_REMINDERS, sanitizeReminders } from '@/lib/reminders/swReminderPayload';

function reminder(noteId: string, fireAt = 1_800_000_000_000) {
  return { noteId, title: 'Reminder', body: 'You have a note reminder', fireAt };
}

/**
 * The service worker takes reminders from a window message and from its own cache, and neither
 * was shape-checked before being armed as a timer. One malformed entry threw and silently took
 * every other reminder for that user with it.
 */
describe('sanitizeReminders', () => {
  it('keeps well-formed reminders unchanged', () => {
    expect(sanitizeReminders([reminder('12'), reminder('7', 1)])).toEqual([
      reminder('12'),
      reminder('7', 1),
    ]);
  });

  it('drops entries whose note id is not a note id', () => {
    const cleaned = sanitizeReminders([
      reminder('12'),
      reminder('../../etc/passwd'),
      reminder(''),
      { ...reminder('9'), noteId: 9 },
    ]);

    expect(cleaned.map((entry) => entry.noteId)).toEqual(['12']);
  });

  it('drops entries with a missing or non-finite fire time', () => {
    const cleaned = sanitizeReminders([
      reminder('1'),
      { ...reminder('2'), fireAt: Number.NaN },
      { ...reminder('3'), fireAt: Number.POSITIVE_INFINITY },
      { ...reminder('4'), fireAt: '1800000000000' },
    ]);

    expect(cleaned.map((entry) => entry.noteId)).toEqual(['1']);
  });

  it('survives junk in place of an array of objects', () => {
    expect(sanitizeReminders(null)).toEqual([]);
    expect(sanitizeReminders('nope')).toEqual([]);
    expect(sanitizeReminders({ reminders: [] })).toEqual([]);
    expect(sanitizeReminders([null, undefined, 42, 'x'])).toEqual([]);
  });

  /** Note text must never reach a lock screen, so the worker owns these strings. */
  it('replaces caller-supplied notification text', () => {
    const cleaned = sanitizeReminders([
      { noteId: '5', title: 'Bank password', body: 'hunter2', fireAt: 1 },
    ]);

    expect(cleaned).toEqual([
      { noteId: '5', title: 'Reminder', body: 'You have a note reminder', fireAt: 1 },
    ]);
  });

  it('bounds how many reminders one payload can arm', () => {
    const many = Array.from({ length: MAX_SW_REMINDERS + 250 }, (_, i) => reminder(String(i)));

    expect(sanitizeReminders(many)).toHaveLength(MAX_SW_REMINDERS);
  });
});
