import { describe, expect, it } from 'vitest';
import { createChecklistItem, sortChecklistItems, type ChecklistItem } from '@/types/checklist';

function item(partial: Partial<ChecklistItem> & Pick<ChecklistItem, 'id' | 'position'>): ChecklistItem {
  return { text: partial.id, isChecked: false, ...partial };
}

describe('createChecklistItem', () => {
  it('defaults the id and checked state', () => {
    const created = createChecklistItem({ text: 'milk', position: 0 });
    expect(created).toMatchObject({ text: 'milk', position: 0, isChecked: false });
    expect(created.id).toMatch(/^chk-/);
  });

  it('generates distinct ids', () => {
    const a = createChecklistItem({ text: 'a', position: 0 });
    const b = createChecklistItem({ text: 'b', position: 1 });
    expect(a.id).not.toBe(b.id);
  });

  it('keeps an explicit id and checked state', () => {
    expect(createChecklistItem({ id: 'chk-1', text: 'milk', position: 2, isChecked: true })).toEqual({
      id: 'chk-1',
      text: 'milk',
      position: 2,
      isChecked: true,
    });
  });
});

describe('sortChecklistItems', () => {
  it('sinks checked items below unchecked ones, each by position', () => {
    const items = [
      item({ id: 'b', position: 1 }),
      item({ id: 'done-late', position: 3, isChecked: true }),
      item({ id: 'a', position: 0 }),
      item({ id: 'done-early', position: 2, isChecked: true }),
    ];
    expect(sortChecklistItems(items).map((i) => i.id)).toEqual([
      'a',
      'b',
      'done-early',
      'done-late',
    ]);
  });

  it('does not mutate the input array', () => {
    const items = [item({ id: 'b', position: 1 }), item({ id: 'a', position: 0 })];
    sortChecklistItems(items);
    expect(items.map((i) => i.id)).toEqual(['b', 'a']);
  });
});
