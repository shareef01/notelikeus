import { describe, expect, it } from 'vitest';
import { readContractFixture } from '@/lib/contract/contractFixtures';
import { localDayStartOfDate, parseNoteQuery, type ParsedQuery } from '@/lib/query/noteQueryParser';

interface Vectors {
  clock: { todayStart: number; dayMillis: number };
  cases: Array<{ input: string; expected: ParsedQuery }>;
}

/**
 * `contracts/query/parser-vectors.json`: the same file Kotlin `QueryParserContractTest` reads, so
 * the web port and `NoteQueryParser` cannot disagree about what an operator means (NL-04).
 */
describe('parseNoteQuery against the cross-client vectors', () => {
  const { clock, cases } = readContractFixture<Vectors>('query/parser-vectors.json');
  const dayStart = (offset: number) => clock.todayStart + offset * clock.dayMillis;
  const utcDayStart = (year: number, month: number, day: number) => {
    const at = new Date(Date.UTC(year, month - 1, day));
    return at.getUTCMonth() === month - 1 && at.getUTCDate() === day ? at.getTime() : null;
  };

  it.each(cases)('$input', ({ input, expected }) => {
    const parsed = parseNoteQuery(input, dayStart, utcDayStart);
    expect({ ...parsed, flags: [...parsed.flags].sort() }).toEqual(expected);
  });
});

describe('localDayStartOfDate', () => {
  it('is local midnight of the typed day, and null for a day that does not exist', () => {
    const at = new Date(localDayStartOfDate(2026, 8, 1)!);
    expect([at.getFullYear(), at.getMonth(), at.getDate(), at.getHours(), at.getMinutes()]).toEqual([2026, 7, 1, 0, 0]);
    expect(localDayStartOfDate(2023, 2, 29)).toBeNull();
  });
});
