import { afterEach, describe, expect, it, vi } from 'vitest';
import { formatListTimestamp, getDateHeader } from '@/lib/text/dateTime';

const NOW = new Date('2025-07-12T16:37:00Z');
const DAY = 24 * 60 * 60 * 1000;

function freezeNow() {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
}

afterEach(() => {
  vi.useRealTimers();
});

describe('getDateHeader', () => {
  it('labels today and yesterday', () => {
    freezeNow();
    expect(getDateHeader(NOW.getTime())).toBe('Today');
    expect(getDateHeader(NOW.getTime() - DAY)).toBe('Yesterday');
  });

  it('omits the year for older dates in the current year', () => {
    freezeNow();
    const header = getDateHeader(new Date('2025-03-04T10:00:00Z').getTime());
    expect(header).not.toMatch(/2025/);
    expect(header).toMatch(/4/);
  });

  it('includes the year for dates in another year', () => {
    freezeNow();
    expect(getDateHeader(new Date('2024-03-04T10:00:00Z').getTime())).toMatch(/2024/);
  });
});

describe('formatListTimestamp', () => {
  it('shows a clock time for today', () => {
    freezeNow();
    const label = formatListTimestamp(NOW.getTime());
    expect(label).toMatch(/\d{1,2}:\d{2}/);
  });

  it('labels yesterday', () => {
    freezeNow();
    expect(formatListTimestamp(NOW.getTime() - DAY)).toBe('Yesterday');
  });

  it('shows a month and day for older dates, never a year', () => {
    freezeNow();
    const label = formatListTimestamp(new Date('2024-03-04T10:00:00Z').getTime());
    expect(label).not.toMatch(/\d{4}/);
    expect(label).toMatch(/4/);
  });
});
