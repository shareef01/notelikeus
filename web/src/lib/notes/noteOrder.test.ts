import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/notes/noteActions', () => ({
  saveNote: vi.fn().mockResolvedValue(undefined),
}));

import { saveNote } from '@/lib/notes/noteActions';
import { commitNotePositions, previewMoveNote } from '@/lib/notes/noteOrder';
import { createEmptyNote, type Note } from '@/types/note';

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id), ...overrides });
}

beforeEach(() => {
  vi.mocked(saveNote).mockClear();
});

describe('previewMoveNote', () => {
  const all = [note('1'), note('2'), note('3'), note('4')];

  it('moves the dragged note to the target index in the full list', () => {
    const result = previewMoveNote(all, all, 0, 2);
    expect(result?.map((n) => n.id)).toEqual(['2', '3', '1', '4']);
  });

  it('moves upward too', () => {
    expect(previewMoveNote(all, all, 3, 1)?.map((n) => n.id)).toEqual(['1', '4', '2', '3']);
  });

  it('maps filtered indices onto the full list', () => {
    const filtered = [all[1], all[3]];
    expect(previewMoveNote(all, filtered, 0, 1)?.map((n) => n.id)).toEqual(['1', '3', '4', '2']);
  });

  it('rejects out-of-range and no-op moves', () => {
    expect(previewMoveNote(all, all, -1, 1)).toBeNull();
    expect(previewMoveNote(all, all, 1, -1)).toBeNull();
    expect(previewMoveNote(all, all, 0, all.length)).toBeNull();
    expect(previewMoveNote(all, all, all.length, 0)).toBeNull();
    expect(previewMoveNote(all, all, 1, 1)).toBeNull();
  });

  it('refuses to move a note across the pinned boundary', () => {
    const notes = [note('1', { isPinned: true }), note('2')];
    expect(previewMoveNote(notes, notes, 0, 1)).toBeNull();
  });

  it('returns null when a filtered note is missing from the full list', () => {
    expect(previewMoveNote([all[0]], [all[0], all[1]], 0, 1)).toBeNull();
  });

  it('does not mutate the source list', () => {
    previewMoveNote(all, all, 0, 2);
    expect(all.map((n) => n.id)).toEqual(['1', '2', '3', '4']);
  });
});

describe('commitNotePositions', () => {
  it('persists only the notes whose position changed', async () => {
    await commitNotePositions([
      note('1', { position: 0 }),
      note('2', { position: 5 }),
      note('3', { position: 2 }),
    ]);

    expect(saveNote).toHaveBeenCalledTimes(1);
    expect(vi.mocked(saveNote).mock.calls[0][0]).toMatchObject({ id: '2', position: 1 });
  });

  it('bumps the edit timestamp on reordered notes', async () => {
    const before = Date.now();
    await commitNotePositions([note('1', { position: 3, timestamp: 1 })]);
    expect(vi.mocked(saveNote).mock.calls[0][0].timestamp).toBeGreaterThanOrEqual(before);
  });

  it('writes nothing when the order already matches', async () => {
    await commitNotePositions([note('1', { position: 0 }), note('2', { position: 1 })]);
    expect(saveNote).not.toHaveBeenCalled();
  });
});
