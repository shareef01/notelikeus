import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import type { Note } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const saveNote = vi.fn(async (_note: Note) => {});
const trashNoteById = vi.fn(async (_id: string) => null);
vi.mock('@/lib/notes/noteActions', () => ({
  saveNote: (note: Note) => saveNote(note),
  trashNoteById: (id: string) => trashNoteById(id),
}));

import { ConflictReviewDialog } from '@/components/notes/ConflictReviewDialog';
import {
  compareVersions,
  findConflictOriginal,
  originalTitleOf,
} from '@/lib/notes/conflictReview';
import { useNotesStore } from '@/store/notesStore';
import { useUiStore } from '@/store/uiStore';
import { createEmptyNote } from '@/types/note';

const original = createEmptyNote({ id: '1', localId: 1, title: 'Groceries', content: 'milk\neggs (from phone)', timestamp: 2000 });
const copy = createEmptyNote({ id: '2', localId: 2, title: 'Groceries (conflicted copy)', content: 'milk\nbread (typed here)', timestamp: 3000 });

describe('conflict review rules', () => {
  it('finds the original by its title', () => {
    expect(originalTitleOf(copy.title)).toBe('Groceries');
    expect(findConflictOriginal(copy, [original, copy])?.id).toBe('1');
  });

  it('refuses to guess between two candidates, a trashed note, or an untitled copy', () => {
    const twin = { ...original, id: '3', localId: 3 };
    expect(findConflictOriginal(copy, [original, twin, copy])).toBeNull();
    expect(findConflictOriginal(copy, [{ ...original, isTrashed: true }, copy])).toBeNull();
    const untitled = { ...copy, title: 'Conflicted copy' };
    expect(findConflictOriginal(untitled, [original, untitled])).toBeNull();
  });

  it('marks the lines only one version has', () => {
    const { a, b } = compareVersions(original, copy);
    expect(a).toEqual([
      { text: 'milk', changed: false },
      { text: 'eggs (from phone)', changed: true },
    ]);
    expect(b.filter((line) => line.changed).map((line) => line.text)).toEqual(['bread (typed here)']);
  });
});

function renderDialog(notes: Note[], reviewId: string) {
  useNotesStore.getState().setNotes(notes);
  useUiStore.getState().openConflictReview(reviewId);
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => root.render(createElement(ConflictReviewDialog)));
  const click = async (name: string) => {
    const button = [...document.querySelectorAll('button')].find((b) => b.textContent === name);
    if (!button) throw new Error(`no button ${name}`);
    await act(async () => {
      button.click();
    });
  };
  return { text: () => document.body.textContent ?? '', click, unmount: () => act(() => root.unmount()) };
}

describe('ConflictReviewDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotesStore.getState().reset();
    useUiStore.getState().closeConflictReview();
  });

  it('shows both versions and keeping the current note moves only the copy to Trash', async () => {
    const view = renderDialog([original, copy], '2');
    expect(view.text()).toContain('Current note');
    expect(view.text()).toContain('eggs (from phone)');
    expect(view.text()).toContain('bread (typed here)');

    await view.click('Keep current note');

    expect(trashNoteById).toHaveBeenCalledWith('2');
    expect(trashNoteById).toHaveBeenCalledTimes(1);
    expect(saveNote).not.toHaveBeenCalled();
    expect(useUiStore.getState().conflictReviewNoteId).toBeNull();
    view.unmount();
  });

  it('keeping the copy trashes the original and gives the copy the original title', async () => {
    const view = renderDialog([original, copy], '2');

    await view.click('Keep conflicted copy');

    expect(trashNoteById).toHaveBeenCalledWith('1');
    expect(saveNote.mock.calls[0]?.[0]).toMatchObject({ id: '2', title: 'Groceries', content: copy.content });
    view.unmount();
  });

  it('keeping both renames the copy and trashes nothing', async () => {
    const view = renderDialog([original, copy], '2');

    await view.click('Keep both');

    expect(trashNoteById).not.toHaveBeenCalled();
    expect(saveNote.mock.calls[0]?.[0]).toMatchObject({ id: '2', title: 'Groceries' });
    view.unmount();
  });

  it('says so when the original cannot be identified, and offers only safe choices', async () => {
    const view = renderDialog([copy], '2');
    expect(view.text()).toContain("couldn't be identified");
    expect(view.text()).not.toContain('Keep conflicted copy');

    await view.click('Move copy to Trash');

    expect(trashNoteById).toHaveBeenCalledWith('2');
    view.unmount();
  });
});
