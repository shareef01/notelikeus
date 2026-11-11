import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/notes/noteActions', () => ({
  saveNote: vi.fn().mockResolvedValue(undefined),
}));

import { saveNote } from '@/lib/notes/noteActions';
import { noteToSupabaseRpcArgs, supabaseNoteToNote } from '@/lib/supabase/supabaseNoteMapper';
import {
  allowsManualReorder,
  commitNotePositions,
  moveBlockReason,
  moveInOrder,
  movedAnnouncement,
  previewMoveNote,
  switchingSortWouldAllowReorder,
} from '@/lib/notes/noteOrder';
import { createEmptyNote, filterNotes, type Note, type NoteQueryFilters } from '@/types/note';

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id), ...overrides });
}

const manual: NoteQueryFilters = { filter: 'active', sortOrder: 'manual' };

beforeEach(() => {
  vi.mocked(saveNote).mockReset().mockResolvedValue(undefined);
});

describe('allowsManualReorder (Kotlin NoteQuery.allowsManualReorder)', () => {
  it('holds under the manual sort with nothing narrowing the list', () => {
    expect(allowsManualReorder(manual)).toBe(true);
    expect(allowsManualReorder({ filter: 'active' })).toBe(true);
    expect(allowsManualReorder({ ...manual, filter: 'archived' })).toBe(true);
  });

  it('is blocked by an automatic sort', () => {
    expect(allowsManualReorder({ ...manual, sortOrder: 'newest' })).toBe(false);
    expect(allowsManualReorder({ ...manual, sortOrder: 'oldest' })).toBe(false);
  });

  it('is blocked by any filter or search', () => {
    expect(allowsManualReorder({ ...manual, searchQuery: 'x' })).toBe(false);
    expect(allowsManualReorder({ ...manual, searchQuery: 'is:pinned' })).toBe(false);
    expect(allowsManualReorder({ ...manual, colors: [1] })).toBe(false);
    expect(allowsManualReorder({ ...manual, labelNames: ['work'] })).toBe(false);
    expect(allowsManualReorder({ ...manual, flags: ['PINNED'] })).toBe(false);
    expect(allowsManualReorder({ ...manual, dateRange: { from: 0, to: 1 } })).toBe(false);
  });

  it('ignores the date field and label match, which narrow nothing on their own', () => {
    expect(allowsManualReorder({ ...manual, dateField: 'CREATED', labelMatch: 'ALL' })).toBe(true);
  });

  it('treats a blank search box as no search', () => {
    expect(allowsManualReorder({ ...manual, searchQuery: '   ' })).toBe(true);
  });

  it('is off in the trash', () => {
    expect(allowsManualReorder({ ...manual, filter: 'trashed' })).toBe(false);
  });

  it('offers the sort switch only when that alone would allow reordering', () => {
    expect(switchingSortWouldAllowReorder({ ...manual, sortOrder: 'newest' })).toBe(true);
    expect(switchingSortWouldAllowReorder(manual)).toBe(false);
    expect(switchingSortWouldAllowReorder({ ...manual, sortOrder: 'newest', labelNames: ['a'] })).toBe(
      false,
    );
    for (const filters of [
      manual,
      { ...manual, sortOrder: 'newest' as const },
      { ...manual, searchQuery: 'x' },
      { ...manual, sortOrder: 'oldest' as const, colors: [2] },
    ]) {
      expect(allowsManualReorder(filters) && switchingSortWouldAllowReorder(filters)).toBe(false);
    }
  });
});

describe('moveBlockReason', () => {
  const list = [note('1', { isPinned: true }), note('2'), note('3')];

  it('allows a step within one group', () => {
    expect(moveBlockReason(list, 1, 1)).toBeNull();
    expect(moveBlockReason(list, 2, -1)).toBeNull();
  });

  it('stops at the ends', () => {
    expect(moveBlockReason(list, 0, -1)).toBe('first');
    expect(moveBlockReason(list, 2, 1)).toBe('last');
  });

  it('keeps pinned notes above the others', () => {
    expect(moveBlockReason(list, 1, -1)).toBe('pinned-boundary');
    expect(moveBlockReason(list, 0, 1)).toBe('pinned-boundary');
  });
});

describe('moveInOrder', () => {
  const list = [note('1'), note('2'), note('3'), note('4')];

  it('moves down and up', () => {
    expect(moveInOrder(list, 0, 2)?.map((n) => n.id)).toEqual(['2', '3', '1', '4']);
    expect(moveInOrder(list, 3, 1)?.map((n) => n.id)).toEqual(['1', '4', '2', '3']);
  });

  it('rejects out-of-range, no-op and cross-pin moves without mutating', () => {
    expect(moveInOrder(list, -1, 1)).toBeNull();
    expect(moveInOrder(list, 0, list.length)).toBeNull();
    expect(moveInOrder(list, 1, 1)).toBeNull();
    expect(moveInOrder([note('1', { isPinned: true }), note('2')], 0, 1)).toBeNull();
    expect(list.map((n) => n.id)).toEqual(['1', '2', '3', '4']);
  });
});

describe('previewMoveNote', () => {
  it('numbers the list as shown, not the store array', () => {
    // The store holds notes in IndexedDB key order, with other piles mixed in.
    const a = note('1', { position: 2 });
    const b = note('2', { position: 0 });
    const c = note('3', { position: 1 });
    const archived = note('9', { position: 0, isArchived: true });
    const store = [a, archived, b, c];
    const shown = filterNotes(store, manual);
    expect(shown.map((n) => n.id)).toEqual(['2', '3', '1']);

    // Move "3" up: it goes first, and the rest keep their order.
    const preview = previewMoveNote(store, shown, 1, 0);
    expect(preview).not.toBeNull();
    expect(filterNotes(preview!.notes, manual).map((n) => n.id)).toEqual(['3', '2', '1']);
    expect(preview!.notes.find((n) => n.id === '9')?.position).toBe(0);
    expect(Object.fromEntries(preview!.previousPositions)).toEqual({ '2': 0, '3': 1 });
  });

  it('does not touch timestamps until the move is committed', () => {
    const store = [note('1', { position: 0, timestamp: 5 }), note('2', { position: 1, timestamp: 6 })];
    const preview = previewMoveNote(store, store, 1, 0)!;
    expect(preview.notes.map((n) => n.timestamp)).toEqual([5, 6]);
  });

  it('returns null when the move is not possible', () => {
    const store = [note('1', { isPinned: true }), note('2')];
    expect(previewMoveNote(store, store, 0, 1)).toBeNull();
    expect(previewMoveNote(store, store, 0, 0)).toBeNull();
  });
});

describe('commitNotePositions', () => {
  it('saves only notes whose position moved, bumping their timestamp for the conflict guard', async () => {
    const notes = [
      note('1', { position: 1, timestamp: 1 }),
      note('2', { position: 0, timestamp: 1 }),
      note('3', { position: 2, timestamp: 1 }),
    ];
    const failed = await commitNotePositions(notes, new Map([['1', 0], ['2', 1]]), 1234);

    expect(failed).toEqual([]);
    expect(vi.mocked(saveNote).mock.calls.map(([saved]) => [saved.id, saved.position, saved.timestamp])).toEqual([
      ['1', 1, 1234],
      ['2', 0, 1234],
    ]);
  });

  it('writes nothing for a note moved and moved back', async () => {
    await commitNotePositions([note('1', { position: 0 })], new Map([['1', 0]]));
    expect(saveNote).not.toHaveBeenCalled();
  });

  it('reports the notes whose save failed', async () => {
    vi.mocked(saveNote).mockImplementation(async (saved) => {
      if (saved.id === '2') throw new Error('offline');
    });
    const failed = await commitNotePositions(
      [note('1', { position: 1 }), note('2', { position: 0 })],
      new Map([['1', 0], ['2', 1]]),
    );
    expect(failed).toEqual(['2']);
  });

  it('syncs the new position through the cloud mapper and back', async () => {
    const notes = [note('1', { position: 1, timestamp: 1 }), note('2', { position: 0, timestamp: 1 })];
    await commitNotePositions(notes, new Map([['1', 0], ['2', 1]]), 5000);
    const saved = vi.mocked(saveNote).mock.calls.map(([n]) => n);

    const roundTripped = saved.map((n) => {
      const args = noteToSupabaseRpcArgs(n, null, 0);
      expect(args.p_position).toBe(n.position);
      expect(args.p_client_timestamp).toBe(5000);
      return supabaseNoteToNote({
        note_id: args.p_note_id,
        local_id: args.p_local_id,
        title: args.p_title,
        content: args.p_content,
        client_timestamp: args.p_client_timestamp,
        color: args.p_color,
        is_pinned: args.p_is_pinned,
        is_archived: args.p_is_archived,
        is_trashed: args.p_is_trashed,
        position: args.p_position,
      });
    });

    // Another device sorts what it downloads by the same positions.
    expect(filterNotes(roundTripped, manual).map((n) => n.id)).toEqual(['2', '1']);
  });
});

describe('movedAnnouncement', () => {
  it('counts from one', () => {
    expect(movedAnnouncement(1, 4)).toBe('Moved to position 1 of 4');
  });
});
