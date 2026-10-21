import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import type { NoteCardProps } from '@/components/notes/NoteCard';
import type { Note } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// Record the props each card is rendered with, instead of rendering the real card.
const rendered: NoteCardProps[] = [];
vi.mock('@/components/notes/NoteCard', () => ({
  NoteCard: (props: NoteCardProps) => {
    rendered.push(props);
    return null;
  },
}));

const { NoteStaggeredGrid } = await import('@/components/notes/NoteStaggeredGrid');

function note(id: string): Note {
  return {
    id,
    localId: Number(id.replace(/\D/g, '')) || 1,
    title: `Note ${id}`,
    content: '',
    color: 0,
    timestamp: 1_700_000_000_000,
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    position: 0,
    reminderTimestamp: null,
    serverUpdatedAt: null,
    labels: [],
    attachments: [],
    checklist: [],
  };
}

const lastPropsFor = (id: string) => [...rendered].reverse().find((props) => props.note.id === id)!;

/**
 * A13: NoteCard is memoised, but the grid gave every card new callbacks on every render, so the
 * memo never held — with 500 notes, ticking one checkbox re-rendered all of them (0.9 s at 4x CPU).
 */
describe('NoteStaggeredGrid card callbacks', () => {
  it('keep their identity across renders, and still call the latest handlers', () => {
    const notes = [note('a'), note('b')];
    const listActions = {
      onArchive: vi.fn(),
      onTrash: vi.fn(),
      onRestore: vi.fn(),
      onPermanentDelete: vi.fn(),
      onPinToggle: vi.fn(),
    };
    const firstClick = vi.fn();
    const latestClick = vi.fn();
    const container = document.createElement('div');
    const root = createRoot(container);
    const render = (onNoteClick: (n: Note) => void, selectedNoteIds: string[], selectionMode = false) =>
      act(() =>
        root.render(
          createElement(NoteStaggeredGrid, {
            notes,
            viewPreference: 2,
            filter: 'active',
            onNoteClick,
            onNoteLongPress: vi.fn(),
            selectedNoteIds,
            selectionMode,
            // A fresh closure every render, as MainScreen passes it.
            onLabelClick: () => {},
            listActions: { ...listActions },
          }),
        ),
      );

    render(firstClick, []);
    const before = lastPropsFor('b');
    // Entering selection mode, as ticking the first checkbox does.
    render(latestClick, ['a'], true);
    const after = lastPropsFor('b');

    for (const key of ['onClick', 'onLongPress', 'onToggleSelect', 'onLabelClick', 'onPinToggle', 'onArchive', 'onTrash'] as const) {
      expect(after[key], key).toBe(before[key]);
    }

    // Stable is not stale: the callback from the first render reaches the second render's handler.
    before.onClick();
    expect(firstClick).not.toHaveBeenCalled();
    expect(latestClick).toHaveBeenCalledWith(notes[1]);

    before.onArchive!();
    expect(listActions.onArchive).toHaveBeenCalledWith(notes[1]);

    act(() => root.unmount());
  });
});
