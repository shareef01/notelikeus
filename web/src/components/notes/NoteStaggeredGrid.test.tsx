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

  it('bounds initial rendering for large note lists and incrementally expands', () => {
    const observeFn = vi.fn();
    const disconnectFn = vi.fn();
    let intersectCallback: IntersectionObserverCallback | null = null;
    const OriginalObserver = globalThis.IntersectionObserver;
    globalThis.IntersectionObserver = class MockIntersectionObserver implements IntersectionObserver {
      readonly root = null;
      readonly rootMargin = '';
      readonly scrollMargin = '';
      readonly thresholds = [];
      constructor(callback: IntersectionObserverCallback) {
        intersectCallback = callback;
      }
      observe = observeFn;
      unobserve = vi.fn();
      disconnect = disconnectFn;
      takeRecords = () => [];
    } as unknown as typeof IntersectionObserver;

    try {
      rendered.length = 0;
      const manyNotes = Array.from({ length: 150 }, (_, i) => note(`n-${i}`));
      const container = document.createElement('div');
      const root = createRoot(container);

      act(() => {
        root.render(
          createElement(NoteStaggeredGrid, {
            notes: manyNotes,
            viewPreference: 2,
            filter: 'active',
            onNoteClick: vi.fn(),
            onNoteLongPress: vi.fn(),
            selectedNoteIds: [],
            selectionMode: false,
          }),
        );
      });

      // Initial chunk renders first 80 notes
      expect(rendered.length).toBe(80);
      expect(observeFn).toHaveBeenCalled();

      // Trigger intersection on sentinel
      act(() => {
        intersectCallback?.(
          [{ isIntersecting: true } as IntersectionObserverEntry],
          {} as IntersectionObserver,
        );
      });

      // Expands by CHUNK_RENDER_COUNT (60), now rendering 140
      const lastRenderedBatch = rendered.slice(-140);
      expect(lastRenderedBatch.length).toBe(140);

      // Now simulate an ordinary update to a note (e.g. background sync or checkbox update)
      rendered.length = 0;
      const updatedNotes = manyNotes.map((n, idx) =>
        idx === 10 ? { ...n, title: 'Updated Note 10' } : n,
      );

      act(() => {
        root.render(
          createElement(NoteStaggeredGrid, {
            notes: updatedNotes,
            viewPreference: 2,
            filter: 'active',
            onNoteClick: vi.fn(),
            onNoteLongPress: vi.fn(),
            selectedNoteIds: [],
            selectionMode: false,
          }),
        );
      });

      // CRITICAL: Must PRESERVE progressive render count (140) and NOT reset to 80!
      expect(rendered.length).toBe(140);

      // But when filter or search changes, it MUST reset to INITIAL_RENDER_COUNT (80)
      rendered.length = 0;
      act(() => {
        root.render(
          createElement(NoteStaggeredGrid, {
            notes: updatedNotes,
            viewPreference: 2,
            filter: 'archived',
            onNoteClick: vi.fn(),
            onNoteLongPress: vi.fn(),
            selectedNoteIds: [],
            selectionMode: false,
          }),
        );
      });

      expect(rendered.length).toBe(80);

      act(() => root.unmount());
    } finally {
      globalThis.IntersectionObserver = OriginalObserver;
    }
  });
});

