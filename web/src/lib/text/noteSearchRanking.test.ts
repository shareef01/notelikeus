import { describe, expect, it } from 'vitest';
import { NOTE_COLOR_OPTIONS } from '@/theme/colors';
import { labelFromName } from '@/types/label';
import { createEmptyNote, searchNotes, type Note } from '@/types/note';
import {
  byRelevance,
  editDistanceWithin,
  fuzzyMatches,
} from '@/lib/text/noteSearchRanking';

const NOW = 1_755_000_000_000;

function note(
  id: string,
  partial: Partial<Note> = {},
): Note {
  return createEmptyNote({
    id,
    localId: Number(id) || 1,
    timestamp: NOW,
    ...partial,
  });
}

describe('byRelevance', () => {
  it('orders title before label before checklist before body', () => {
    const inTitle = note('1', { title: 'budget' });
    const inLabel = note('2', { title: 'x', labels: [labelFromName('budget')] });
    const inChecklist = note('3', {
      title: 'x',
      checklist: [{ id: 'c', text: 'budget', isChecked: false, position: 0 }],
    });
    const inBody = note('4', { title: 'x', content: 'budget' });

    expect(
      byRelevance([inBody, inChecklist, inLabel, inTitle], 'budget').map((n) => n.id),
    ).toEqual(['1', '2', '3', '4']);
  });

  it('an exact title beats a title that merely contains the words', () => {
    const exact = note('1', { title: 'Weekly budget' });
    const longer = note('2', { title: 'Weekly budget review and planning notes' });
    expect(byRelevance([longer, exact], 'weekly budget').map((n) => n.id)).toEqual(['1', '2']);
  });

  it('matching both words in the title beats splitting them across fields', () => {
    const both = note('1', { title: 'milk bread' });
    const split = note('2', { title: 'milk', content: 'bread' });
    expect(byRelevance([split, both], 'milk bread').map((n) => n.id)).toEqual(['1', '2']);
  });

  it('equal relevance falls back to most recently edited', () => {
    const older = note('1', { title: 'budget', timestamp: NOW - 1000 });
    const newer = note('2', { title: 'budget', timestamp: NOW });
    expect(byRelevance([older, newer], 'budget').map((n) => n.id)).toEqual(['2', '1']);
  });

  it('pinned notes still lead even when they score lower', () => {
    const pinnedWeak = note('1', { title: 'x', content: 'budget', isPinned: true });
    const unpinnedStrong = note('2', { title: 'budget' });
    expect(byRelevance([unpinnedStrong, pinnedWeak], 'budget').map((n) => n.id)).toEqual([
      '1',
      '2',
    ]);
  });

  it('an empty query leaves the order alone', () => {
    const notes = [note('1'), note('2'), note('3')];
    expect(byRelevance(notes, '  ').map((n) => n.id)).toEqual(['1', '2', '3']);
  });
});

describe('editDistanceWithin', () => {
  it('identical strings are zero edits apart', () => {
    expect(editDistanceWithin('budget', 'budget', 0)).toBe(true);
  });

  it('counts substitutions insertions and deletions', () => {
    expect(editDistanceWithin('budget', 'budgot', 1)).toBe(true);
    expect(editDistanceWithin('budget', 'budgets', 1)).toBe(true);
    expect(editDistanceWithin('budget', 'budgt', 1)).toBe(true);
    expect(editDistanceWithin('budget', 'budgot', 0)).toBe(false);
  });

  it('respects the budget', () => {
    expect(editDistanceWithin('kitten', 'sitting', 3)).toBe(true);
    expect(editDistanceWithin('kitten', 'sitting', 2)).toBe(false);
  });

  it('a length gap wider than the budget is rejected immediately', () => {
    expect(editDistanceWithin('a', 'abcdefgh', 2)).toBe(false);
    expect(editDistanceWithin('abcdefgh', 'a', 2)).toBe(false);
  });

  it('empty strings behave', () => {
    expect(editDistanceWithin('', '', 0)).toBe(true);
    expect(editDistanceWithin('', 'ab', 2)).toBe(true);
    expect(editDistanceWithin('', 'abc', 2)).toBe(false);
  });
});

describe('fuzzy fallback via searchNotes', () => {
  const notes = [
    note('1', { title: 'Budget review' }),
    note('2', { title: 'Groceries' }),
    note('3', { title: 'Holiday plans' }),
  ];

  it('an exact match never triggers the fallback', () => {
    const result = searchNotes(notes, { filter: 'active', searchQuery: 'budget' });
    expect(result.isFuzzy).toBe(false);
    expect(result.notes.map((n) => n.id)).toEqual(['1']);
  });

  it('a typo falls back to near matches and says so', () => {
    const result = searchNotes(notes, { filter: 'active', searchQuery: 'budgte' });
    expect(result.isFuzzy).toBe(true);
    expect(result.notes.map((n) => n.id)).toEqual(['1']);
  });

  it('something genuinely absent still returns nothing', () => {
    const result = searchNotes(notes, { filter: 'active', searchQuery: 'submarine' });
    expect(result.isFuzzy).toBe(false);
    expect(result.notes).toEqual([]);
  });

  it('short tokens are not corrected', () => {
    const prefix = searchNotes(notes, { filter: 'active', searchQuery: 'bud' });
    expect(prefix.isFuzzy).toBe(false);
    expect(prefix.notes.map((n) => n.id)).toEqual(['1']);

    const nonsense = searchNotes(notes, { filter: 'active', searchQuery: 'xyz' });
    expect(nonsense.notes).toEqual([]);
  });

  it('the fallback keeps every non-text filter exact', () => {
    const { light } = NOTE_COLOR_OPTIONS[1];
    const other = NOTE_COLOR_OPTIONS[2].light;
    const coloured = notes.map((n) => ({ ...n, color: light }));
    const result = searchNotes(coloured, {
      filter: 'active',
      searchQuery: 'budgte',
      colorArgb: other,
    });
    expect(result.notes).toEqual([]);
  });

  it('an empty text query is never fuzzy', () => {
    const result = searchNotes(notes, { filter: 'active' });
    expect(result.isFuzzy).toBe(false);
    expect(result.notes).toHaveLength(3);
  });
});

describe('fuzzyMatches', () => {
  it('returns nothing for a blank query', () => {
    expect(fuzzyMatches([note('1', { title: 'Budget' })], '   ')).toEqual([]);
  });
});
