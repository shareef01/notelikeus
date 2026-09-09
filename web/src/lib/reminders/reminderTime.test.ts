import { afterEach, describe, expect, it } from 'vitest';
import {
  fromLocalDateTimeInputValue,
  initialReminderInputValue,
  nextWeek,
  nextWholeHour,
  ONE_HOUR_MS,
  reminderPresets,
  toLocalDateTimeInputValue,
  tomorrowMorning,
} from '@/lib/reminders/reminderTime';

/**
 * Time-zone behaviour of the reminder picker.
 *
 * `process.env.TZ` is how Node's `Date` picks its zone, and it is re-read per `Date` construction,
 * so setting it inside a test is enough. The zones are chosen for what each one breaks — see the
 * matching Kotlin suite in `ReminderTimeTest.kt`.
 */
const originalTz = process.env.TZ;

afterEach(() => {
  process.env.TZ = originalTz;
});

function inZone(tz: string, run: () => void) {
  process.env.TZ = tz;
  try {
    run();
  } finally {
    process.env.TZ = originalTz;
  }
}

/** Local wall-clock fields of an instant, read back independently of the code under test. */
function localFields(timestamp: number) {
  const date = new Date(timestamp);
  return [
    date.getFullYear(),
    date.getMonth() + 1,
    date.getDate(),
    date.getHours(),
    date.getMinutes(),
  ];
}

describe('toLocalDateTimeInputValue', () => {
  it('writes local wall-clock time, not UTC', () => {
    inZone('Asia/Kolkata', () => {
      // 2026-07-08T04:00:00Z is 09:30 local in UTC+5:30.
      const value = toLocalDateTimeInputValue(Date.UTC(2026, 6, 8, 4, 0));
      expect(value).toBe('2026-07-08T09:30');
    });
  });

  it('does not slip a day west of UTC', () => {
    inZone('Pacific/Niue', () => {
      // 2026-07-08T05:00:00Z is 18:00 on July 7 in UTC-11.
      const value = toLocalDateTimeInputValue(Date.UTC(2026, 6, 8, 5, 0));
      expect(value).toBe('2026-07-07T18:00');
    });
  });

  it('round-trips through the input value in every zone tried', () => {
    for (const tz of ['UTC', 'Asia/Kolkata', 'Pacific/Niue', 'America/New_York', 'Australia/Eucla']) {
      inZone(tz, () => {
        const instant = Date.UTC(2026, 6, 8, 12, 34);
        const value = toLocalDateTimeInputValue(instant);
        // Seconds are not representable in the input, so compare at minute resolution.
        expect(fromLocalDateTimeInputValue(value)).toBe(instant);
      });
    }
  });
});

describe('fromLocalDateTimeInputValue', () => {
  it('parses the input as local time', () => {
    inZone('Asia/Kolkata', () => {
      expect(fromLocalDateTimeInputValue('2026-07-08T09:30')).toBe(Date.UTC(2026, 6, 8, 4, 0));
    });
  });

  it('rejects a value the picker can leave behind', () => {
    expect(fromLocalDateTimeInputValue('')).toBeNull();
    expect(fromLocalDateTimeInputValue('2026-07-08')).toBeNull();
    expect(fromLocalDateTimeInputValue('not a date')).toBeNull();
    expect(fromLocalDateTimeInputValue('2026-13-40T99:99')).toBeNull();
  });

  it('resolves a wall-clock time spring-forward skips', () => {
    inZone('America/New_York', () => {
      // 02:30 does not exist on 2026-03-08; it must resolve forward rather than to NaN.
      const parsed = fromLocalDateTimeInputValue('2026-03-08T02:30');
      expect(parsed).not.toBeNull();
      expect(localFields(parsed as number).slice(0, 3)).toEqual([2026, 3, 8]);
    });
  });
});

describe('initialReminderInputValue', () => {
  it('opens on the existing reminder, unchanged', () => {
    inZone('Pacific/Niue', () => {
      const existing = Date.UTC(2026, 6, 8, 5, 0);
      const value = initialReminderInputValue(existing);
      expect(fromLocalDateTimeInputValue(value)).toBe(existing);
    });
  });

  it('opens on the next whole local hour when nothing is set', () => {
    inZone('Asia/Kolkata', () => {
      // 09:47 local on 2026-07-08.
      const now = Date.UTC(2026, 6, 8, 4, 17);
      expect(initialReminderInputValue(null, now)).toBe('2026-07-08T10:00');
    });
  });

  it('rolls onto the next local day just before midnight', () => {
    inZone('Europe/Berlin', () => {
      const now = new Date(2026, 6, 8, 23, 12).getTime();
      expect(initialReminderInputValue(null, now)).toBe('2026-07-09T00:00');
    });
  });

  it('never opens on a moment already past', () => {
    inZone('America/New_York', () => {
      const now = Date.now();
      const opened = fromLocalDateTimeInputValue(initialReminderInputValue(null, now));
      expect(opened).not.toBeNull();
      expect(opened as number).toBeGreaterThan(now);
      expect((opened as number) - now).toBeLessThanOrEqual(ONE_HOUR_MS);
    });
  });
});

describe('presets', () => {
  it('offers the same three choices as the Kotlin clients', () => {
    expect(reminderPresets(Date.now()).map((preset) => preset.label)).toEqual([
      'In 1 hour',
      'Tomorrow 9:00',
      'Next week',
    ]);
  });

  it('puts tomorrow morning at 09:00 local', () => {
    inZone('America/New_York', () => {
      const now = new Date(2026, 6, 8, 22, 30).getTime();
      expect(localFields(tomorrowMorning(now))).toEqual([2026, 7, 9, 9, 0]);
    });
  });

  it('keeps 09:00 across a spring-forward boundary', () => {
    inZone('America/New_York', () => {
      // 2026-03-07 22:30 local; tomorrow is the day the clocks go forward.
      const now = new Date(2026, 2, 7, 22, 30).getTime();
      expect(localFields(tomorrowMorning(now))).toEqual([2026, 3, 8, 9, 0]);
    });
  });

  it('adds a calendar week rather than seven fixed days', () => {
    inZone('America/New_York', () => {
      // 2026-03-05 15:00 local; the week ahead contains a DST change, so a fixed
      // 7 * 86_400_000 would land at 14:00 instead.
      const now = new Date(2026, 2, 5, 15, 0).getTime();
      expect(localFields(nextWeek(now))).toEqual([2026, 3, 12, 15, 0]);
    });
  });

  it('puts the one-hour preset exactly an hour out', () => {
    const now = Date.now();
    const preset = reminderPresets(now).find((entry) => entry.id === 'in-one-hour');
    expect(preset?.at).toBe(now + ONE_HOUR_MS);
  });

  it('nextWholeHour lands on the hour', () => {
    inZone('Australia/Eucla', () => {
      // UTC+8:45 — a zone whose offset is not a whole hour, which is where an
      // offset-arithmetic implementation lands on :45 instead of :00.
      const rounded = nextWholeHour(new Date(2026, 6, 8, 13, 21).getTime());
      expect(localFields(rounded)).toEqual([2026, 7, 8, 14, 0]);
    });
  });
});
