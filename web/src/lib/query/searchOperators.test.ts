import { describe, expect, it } from 'vitest';
import { createEmptyNote, searchNotes, type Note } from '@/types/note';
import { NOTE_COLOR_OPTIONS } from '@/theme/colors';
import { activeSmartView, smartViewCounts } from '@/lib/query/smartViews';

const NOW = new Date(2026, 7, 12, 15, 0).getTime();
const DAY = 86_400_000;
let nextId = 1;

function note(title: string, partial: Partial<Note> = {}): Note {
  const id = nextId++;
  return createEmptyNote({ id: `n${id}`, localId: id, title, timestamp: NOW - DAY, ...partial });
}

const label = (name: string) => ({ id: null, name }) as unknown as Note['labels'][number];
const item = (text: string, isChecked: boolean) =>
  ({ id: null, text, isChecked, position: 0 }) as unknown as Note['checklist'][number];

function titles(notes: Note[], searchQuery: string, extra: Partial<Parameters<typeof searchNotes>[1]> = {}) {
  return searchNotes(notes, { filter: 'active', searchQuery, ...extra }, { now: NOW }).notes.map((n) => n.title).sort();
}

/** NL-04: web searched for `label:work` as literal text; it is a filter, as on Android and Windows. */
describe('search operators on web', () => {
  const work = note('Quarterly plan', { labels: [label('Work')] });
  const home = note('Groceries', { labels: [label('Home')], checklist: [item('milk', false)] });
  const done = note('Packing', { checklist: [item('socks', true)] });
  const pinned = note('Pinned idea', { isPinned: true });
  const green = note('Garden', { color: NOTE_COLOR_OPTIONS[4]!.dark });
  const reminder = note('Dentist', { reminderTimestamp: NOW - 60_000 });
  const later = note('Flight', { reminderTimestamp: NOW + DAY });
  const old = note('Old receipt', { timestamp: new Date(2025, 0, 10).getTime() });
  const archived = note('Archived plan', { isArchived: true, labels: [label('Work')] });
  const trashed = note('Trashed plan', { isTrashed: true });
  const link = note('Reading', { content: 'see https://example.com' });
  const all = [work, home, done, pinned, green, reminder, later, old, archived, trashed, link];

  it('label: filters by label, case-insensitively, and leaves the free text to search', () => {
    expect(titles(all, 'label:work')).toEqual(['Quarterly plan']);
    expect(titles(all, 'LABEL:Work plan')).toEqual(['Quarterly plan']);
    expect(titles(all, 'label:work label:home')).toEqual(['Groceries', 'Quarterly plan']);
  });

  it('a label nobody has narrows nothing, as on native', () => {
    expect(titles(all, 'label:nowhere')).toHaveLength(9);
  });

  it('color: matches the colour on either theme', () => {
    expect(titles(all, 'color:green')).toEqual(['Garden']);
    expect(titles(all, 'colour:GREEN')).toEqual(['Garden']);
  });

  it('is: and has: flags', () => {
    expect(titles(all, 'is:pinned')).toEqual(['Pinned idea']);
    expect(titles(all, 'has:reminder')).toEqual(['Dentist', 'Flight']);
    expect(titles(all, 'is:overdue')).toEqual(['Dentist']);
    expect(titles(all, 'has:checklist')).toEqual(['Groceries', 'Packing']);
    expect(titles(all, 'has:unchecked')).toEqual(['Groceries']);
    expect(titles(all, 'has:link')).toEqual(['Reading']);
    expect(titles(all, 'is:unlabeled')).not.toContain('Quarterly plan');
  });

  it('in: and is:archived choose the pile over the drawer', () => {
    expect(titles(all, 'in:trash')).toEqual(['Trashed plan']);
    expect(titles(all, 'is:archived label:work')).toEqual(['Archived plan']);
    expect(titles(all, 'in:all plan')).toEqual(['Archived plan', 'Quarterly plan', 'Trashed plan']);
  });

  it('before: and after: bound the edited date', () => {
    expect(titles(all, 'before:2026-01-01')).toEqual(['Old receipt']);
    expect(titles(all, 'after:2026-01-01')).not.toContain('Old receipt');
    expect(titles(all, 'after:yesterday')).toHaveLength(8);
  });

  it('reports what it could not use, searches only the free text, and keeps colons in ordinary text', () => {
    const result = searchNotes(all, { filter: 'active', searchQuery: 'is:nonsense label: plan' }, { now: NOW });
    expect(result.unknownOperators).toEqual(['is:nonsense', 'label:']);
    expect(result.searchText).toBe('plan');
    expect(result.notes.map((n) => n.title)).toEqual(['Quarterly plan']);

    const todo = note('TODO: fix the fence');
    expect(searchNotes([todo], { filter: 'active', searchQuery: 'TODO: fix' }).notes).toEqual([todo]);
  });

  it('still combines with the chips', () => {
    expect(titles(all, 'has:checklist', { labelName: 'Home' })).toEqual(['Groceries']);
  });
});

describe('smart views', () => {
  it('count active notes per view, and recognise the view on screen', () => {
    const notes = [
      note('a', { reminderTimestamp: NOW }),
      note('b', { checklist: [item('x', false)], labels: [label('L')] }),
      note('c', { isArchived: true, reminderTimestamp: NOW }),
    ];
    expect(smartViewCounts(notes, NOW)).toEqual({ reminders: 1, unfinished: 1, unlabeled: 1 });
    expect(activeSmartView({ filter: 'active', searchQuery: 'has:reminder' })).toBe('reminders');
    expect(activeSmartView({ filter: 'active', searchQuery: 'has:reminder', labelName: 'L' })).toBeNull();
    expect(activeSmartView({ filter: 'archived', searchQuery: 'has:reminder' })).toBeNull();
  });
});
