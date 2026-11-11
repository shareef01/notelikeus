import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, createElement, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { FiltersSheet, type FiltersSheetProps } from '@/components/layout/FiltersSheet';
import { labelFromName } from '@/types/label';
import type { NoteQueryFilters } from '@/types/note';
import { NOTE_COLOR_OPTIONS } from '@/theme/colors';
import { datePresetRange, hasActiveFilters } from '@/lib/query/noteQuery';
import { SAVED_FILTER_MAX, withSavedFilter, type SavedFilter } from '@/lib/query/savedFilters';

const NOW = new Date(2026, 10, 11, 12).getTime();

let root: Root | null = null;
let container: HTMLDivElement | null = null;

afterEach(() => {
  act(() => root?.unmount());
  container?.remove();
  root = null;
  container = null;
});

/** Renders the sheet over real state, so a sequence of taps composes the way it does in the app. */
function renderSheet(
  initial: NoteQueryFilters = { filter: 'active' },
  overrides: Partial<FiltersSheetProps> = {},
) {
  const latest: { filters: NoteQueryFilters } = { filters: initial };
  function Harness() {
    const [filters, setFilters] = useState<NoteQueryFilters>(initial);
    latest.filters = filters;
    return createElement(FiltersSheet, {
      open: true,
      onClose: () => {},
      filters,
      onChange: (patch) => setFilters((current) => ({ ...current, ...patch })),
      onClearAll: () =>
        setFilters((current) => ({ ...current, searchQuery: '', colors: [], labelNames: [], flags: [], dateRange: null })),
      labels: [labelFromName('Work'), labelFromName('Home'), labelFromName('Urgent')],
      matchCount: 3,
      savedFilters: [],
      onApplySaved: () => {},
      onDeleteSaved: () => {},
      onSave: () => {},
      now: NOW,
      ...overrides,
    });
  }
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  act(() => root!.render(createElement(Harness)));
  return latest;
}

function button(name: string): HTMLButtonElement {
  const match = Array.from(document.querySelectorAll('button')).find(
    (el) => el.getAttribute('aria-label') === name || el.textContent?.trim() === name,
  );
  if (!match) throw new Error(`no button "${name}"`);
  return match;
}

function click(name: string) {
  act(() => button(name).click());
}

function type(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
  act(() => {
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

describe('FiltersSheet', () => {
  it('combines several colours, labels, flags, a date window and a scope', () => {
    const state = renderSheet();
    click('Red');
    click('Blue');
    click('Work');
    click('Urgent');
    click('Pinned');
    click('Has links');
    click('Reminder');
    click('Last 7 days');
    click('Archive');

    const f = state.filters;
    const coral = NOTE_COLOR_OPTIONS[1]!;
    const blue = NOTE_COLOR_OPTIONS[6]!;
    expect([...(f.colors ?? [])].sort()).toEqual([coral.light, coral.dark, blue.light, blue.dark].sort());
    expect(f.labelNames).toEqual(['Work', 'Urgent']);
    expect(f.flags).toEqual(['PINNED', 'HAS_LINKS']);
    expect(f.dateField).toBe('REMINDER');
    expect(f.dateRange).toEqual(datePresetRange('LAST_7', NOW));
    expect(f.filter).toBe('archived');
    expect(button('Last 7 days').getAttribute('aria-pressed')).toBe('true');
    expect(button('Pinned').getAttribute('aria-pressed')).toBe('true');
  });

  it('a second tap turns a choice back off', () => {
    const state = renderSheet();
    click('Green');
    click('Green');
    click('Has checklist');
    click('Has checklist');
    expect(state.filters.colors).toEqual([]);
    expect(state.filters.flags).toEqual([]);
  });

  it('offers any/all only once two labels are chosen, and toggles between them', () => {
    const state = renderSheet();
    click('Work');
    expect(() => button('Any of these')).toThrow();
    click('Home');
    expect(button('Any of these').getAttribute('aria-pressed')).toBe('false');
    click('Any of these');
    expect(state.filters.labelMatch).toBe('ALL');
    expect(button('All of these').getAttribute('aria-pressed')).toBe('true');
    click('All of these');
    expect(state.filters.labelMatch).toBe('ANY');
  });

  it('Any time removes the date window', () => {
    const state = renderSheet({ filter: 'active', dateRange: datePresetRange('TODAY', NOW) });
    expect(button('Today').getAttribute('aria-pressed')).toBe('true');
    click('Any time');
    expect(state.filters.dateRange).toBeNull();
  });

  it('Clear all keeps the scope, date field and label match (cleared())', () => {
    const state = renderSheet({
      filter: 'trashed',
      labelMatch: 'ALL',
      dateField: 'CREATED',
      labelNames: ['Work'],
      flags: ['UNTITLED'],
    });
    click('Clear all');
    expect(hasActiveFilters(state.filters)).toBe(false);
    expect(state.filters).toMatchObject({ filter: 'trashed', labelMatch: 'ALL', dateField: 'CREATED' });
  });

  it('cannot save or clear when nothing narrows', () => {
    renderSheet();
    expect(button('Save these filters').disabled).toBe(true);
    expect(button('Clear all').disabled).toBe(true);
    expect(button('Show 3 notes')).toBeTruthy();
  });

  it('names and saves what is on screen', () => {
    const onSave = vi.fn();
    renderSheet({ filter: 'active', flags: ['PINNED'] }, { onSave });
    click('Save these filters');
    const input = document.querySelector<HTMLInputElement>('input[placeholder="e.g. Invoices this month"]')!;
    expect(button('Save').disabled).toBe(true);
    type(input, '  Pinned stuff  ');
    click('Save');
    expect(onSave).toHaveBeenCalledWith('Pinned stuff');
    expect(document.querySelector('input[placeholder="e.g. Invoices this month"]')).toBeNull();
  });

  it('warns when a name replaces a saved filter, or the list is full', () => {
    const saved = withSavedFilter([], 'Invoices', { filter: 'active' });
    renderSheet({ filter: 'active', flags: ['PINNED'] }, { savedFilters: saved });
    click('Save these filters');
    const input = document.querySelector<HTMLInputElement>('input[placeholder="e.g. Invoices this month"]')!;
    type(input, 'invoices');
    expect(document.body.textContent).toContain('Replaces the filter already saved as “Invoices”');

    act(() => root!.unmount());
    let full: SavedFilter[] = [];
    for (let i = 0; i < SAVED_FILTER_MAX; i++) full = withSavedFilter(full, `f${i}`, { filter: 'active' });
    root = createRoot(container!);
    renderSheet({ filter: 'active', flags: ['PINNED'] }, { savedFilters: full });
    click('Save these filters');
    expect(document.body.textContent).toContain(`${SAVED_FILTER_MAX} saved already. Saving replaces the oldest.`);
  });

  it('lists saved filters, marks the one on screen, applies and deletes them', () => {
    const onApplySaved = vi.fn();
    const onDeleteSaved = vi.fn();
    const saved = withSavedFilter(withSavedFilter([], 'Pinned', { filter: 'active', flags: ['PINNED'] }), 'Old', {
      filter: 'archived',
    });
    renderSheet({ filter: 'active', flags: ['PINNED'], sortOrder: 'oldest' }, {
      savedFilters: saved,
      onApplySaved,
      onDeleteSaved,
    });
    expect(button('Pinned').getAttribute('aria-pressed')).toBe('true');
    const row = Array.from(document.querySelectorAll('li button')).find((el) => el.textContent === 'Pinned')!;
    expect(row.getAttribute('aria-current')).toBe('true');

    act(() => (Array.from(document.querySelectorAll('li button')).find((el) => el.textContent === 'Old') as HTMLElement).click());
    expect(onApplySaved).toHaveBeenCalledWith(saved[0]);
    click('Delete saved filter Old');
    expect(onDeleteSaved).toHaveBeenCalledWith('Old');
  });

  it('keeps label chips in place while toggling, leading with those chosen at open', () => {
    renderSheet({ filter: 'active', labelNames: ['Urgent'] });
    const order = () =>
      Array.from(document.querySelectorAll('button'))
        .map((el) => el.textContent)
        .filter((text) => text === 'Home' || text === 'Work' || text === 'Urgent');
    expect(order()).toEqual(['Urgent', 'Home', 'Work']);
    click('Work');
    click('Urgent');
    // A chip that moved on each tap put the next one under the pointer.
    expect(order()).toEqual(['Urgent', 'Home', 'Work']);
  });

  it('Escape in the name field cancels naming and leaves the sheet open', () => {
    const onClose = vi.fn();
    renderSheet({ filter: 'active', flags: ['PINNED'] }, { onClose });
    click('Save these filters');
    const input = document.querySelector<HTMLInputElement>('input[placeholder="e.g. Invoices this month"]')!;
    act(() => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(document.querySelector('input[placeholder="e.g. Invoices this month"]')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('shows a label search once there are many labels', () => {
    const labels = Array.from({ length: 9 }, (_, i) => labelFromName(`L${i}`));
    renderSheet({ filter: 'active' }, { labels });
    const search = document.querySelector<HTMLInputElement>('input[aria-label="Search labels"]')!;
    type(search, 'L3');
    const labelChips = Array.from(document.querySelectorAll('button')).filter((el) => /^L\d$/.test(el.textContent ?? ''));
    expect(labelChips.map((el) => el.textContent)).toEqual(['L3']);
  });
});
