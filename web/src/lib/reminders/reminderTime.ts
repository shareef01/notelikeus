/**
 * The instants a reminder can be set to, and the conversions the picker needs.
 *
 * The Kotlin counterpart is `ReminderTime.kt`, and the presets below resolve to the same moments
 * its `DateUtils` does — `getTomorrowMorning` is 09:00 local tomorrow, `getNextWeek` is the same
 * time of day seven days out. Keeping the arithmetic here, rather than inline in the dialog, is
 * what lets the time-zone and DST behaviour be tested without a browser.
 *
 * Every function works in the **viewer's local zone**. That is the whole hazard: a
 * `datetime-local` input speaks local wall-clock time and `Date.prototype.toISOString` speaks
 * UTC, and mixing them shifts a reminder by the zone offset.
 */

export const ONE_HOUR_MS = 60 * 60 * 1000;

export type ReminderPresetId = 'in-one-hour' | 'tomorrow-morning' | 'next-week';

export interface ReminderPreset {
  id: ReminderPresetId;
  label: string;
  at: number;
}

/** The next whole hour after `now`, in local time. */
export function nextWholeHour(now: number = Date.now()): number {
  const date = new Date(now);
  date.setMinutes(0, 0, 0);
  date.setHours(date.getHours() + 1);
  return date.getTime();
}

/** 09:00 local time tomorrow. Mirrors Kotlin `DateUtils.getTomorrowMorning`. */
export function tomorrowMorning(now: number = Date.now()): number {
  const date = new Date(now);
  date.setDate(date.getDate() + 1);
  date.setHours(9, 0, 0, 0);
  return date.getTime();
}

/**
 * The same time of day, seven days out. Mirrors Kotlin `DateUtils.getNextWeek`, which adds a
 * calendar week rather than `7 * 86_400_000` — across a DST change those differ by an hour, and
 * the calendar answer is the one a user means by "next week".
 */
export function nextWeek(now: number = Date.now()): number {
  const date = new Date(now);
  date.setDate(date.getDate() + 7);
  return date.getTime();
}

export function reminderPresets(now: number = Date.now()): ReminderPreset[] {
  return [
    { id: 'in-one-hour', label: 'In 1 hour', at: now + ONE_HOUR_MS },
    { id: 'tomorrow-morning', label: 'Tomorrow 9:00', at: tomorrowMorning(now) },
    { id: 'next-week', label: 'Next week', at: nextWeek(now) },
  ];
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/**
 * `timestamp` as the `YYYY-MM-DDTHH:mm` string a `datetime-local` input expects.
 *
 * Built from the local getters rather than from `toISOString().slice(0, 16)`. That shortcut is
 * correct only at UTC+0: everywhere else it hands the input a wall-clock time offset by the
 * viewer's zone, so the picker opens on the wrong hour — and west of UTC, on the wrong day.
 */
export function toLocalDateTimeInputValue(timestamp: number): string {
  const date = new Date(timestamp);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/**
 * The instant a `datetime-local` value names, in the viewer's own zone.
 *
 * `new Date('2026-07-08T09:30')` — no trailing `Z`, no offset — is parsed as local time by every
 * current engine, which is what this input means. Returns null for a value the picker can leave
 * behind: empty, or half-typed.
 *
 * A wall-clock time a spring-forward change skips resolves to the first instant that does exist,
 * matching the Kotlin path's `combineDateAndTime`.
 */
export function fromLocalDateTimeInputValue(value: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value)) return null;
  const parsed = new Date(value).getTime();
  return Number.isFinite(parsed) ? parsed : null;
}

/** What the picker should open on: the existing reminder, or the next whole hour. */
export function initialReminderInputValue(
  existingReminder: number | null,
  now: number = Date.now(),
): string {
  return toLocalDateTimeInputValue(existingReminder ?? nextWholeHour(now));
}
