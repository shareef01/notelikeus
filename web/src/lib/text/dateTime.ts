/**
 * Smart Date Formatting
 * Organizes timestamps into logical groups (Today, Yesterday, etc.)
 *
 * Both functions run once per note on every render of the notes list. They used to build a new
 * Intl formatter (directly, or through toLocaleDateString with options) and compare
 * toDateString() strings on every call, which with 500 notes was ~90 ms of every list render in a
 * profile (A13). Formatters are now built once, and days are compared as numbers; the output is
 * the same.
 */
const formatters = new Map<string, Intl.DateTimeFormat>();
function formatter(options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = JSON.stringify(options);
  let cached = formatters.get(key);
  if (!cached) {
    cached = new Intl.DateTimeFormat(undefined, options);
    formatters.set(key, cached);
  }
  return cached;
}

/** A number unique to the local calendar day. */
function dayKey(date: Date): number {
  return date.getFullYear() * 10_000 + date.getMonth() * 100 + date.getDate();
}

function relativeDay(date: Date): 'today' | 'yesterday' | null {
  const now = new Date();
  const key = dayKey(date);
  if (key === dayKey(now)) return 'today';
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  return key === dayKey(yesterday) ? 'yesterday' : null;
}

export function getDateHeader(timestamp: number): string {
  const date = new Date(timestamp);
  const day = relativeDay(date);
  if (day === 'today') return 'Today';
  if (day === 'yesterday') return 'Yesterday';

  const sameYear = date.getFullYear() === new Date().getFullYear();
  return formatter(
    sameYear ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' },
  ).format(date);
}

/** Compact time label for list rows (e.g. "4:37 PM" or "Jul 12"). */
export function formatListTimestamp(timestamp: number): string {
  const date = new Date(timestamp);
  const day = relativeDay(date);
  if (day === 'today') return formatter({ hour: 'numeric', minute: '2-digit' }).format(date);
  if (day === 'yesterday') return 'Yesterday';
  return formatter({ month: 'short', day: 'numeric' }).format(date);
}
