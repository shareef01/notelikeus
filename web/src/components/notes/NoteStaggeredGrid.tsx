import { NoteCard, type NoteCardDensity, type NoteReorderHandleProps } from '@/components/notes/NoteCard';
import { NoteSectionHeader } from '@/components/notes/NoteSectionHeader';
import { SwipeableNoteCard } from '@/components/notes/SwipeableNoteCard';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { getDateHeader } from '@/lib/text/dateTime';
import type { ViewColumns } from '@/store/uiStore';
import type { Note, NoteFilter } from '@/types/note';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

interface NoteListActions {
  onArchive: (note: Note) => void;
  onTrash: (note: Note) => void;
  onRestore: (note: Note) => void;
  onPermanentDelete: (note: Note) => void;
  onPinToggle?: (note: Note) => void;
}

interface NoteStaggeredGridProps {
  notes: Note[];
  /** User view preference — drives card min-width + density. */
  viewPreference: ViewColumns;
  filter: NoteFilter;
  sortOrder?: 'manual' | 'newest' | 'oldest';
  onNoteClick: (note: Note) => void;
  onNoteLongPress: (note: Note) => void;
  selectedNoteIds: string[];
  selectionMode: boolean;
  onLabelClick?: (labelName: string) => void;
  listActions?: NoteListActions;
  searchQuery?: string;
  allowReorder?: boolean;
  onMoveNote?: (fromIndex: number, toIndex: number) => void;
  onReorderComplete?: () => void;
}

/**
 * Grid/dense pack with CSS columns (masonry-like). List is a single readable column.
 * Column widths differ enough that List / Grid / Compact feel like real size options.
 */
const CARD_MIN_PX: Record<2 | 3, number> = {
  /** Comfortable Keep-style cards */
  2: 300,
  /** Compact multi-column board */
  3: 200,
};

const DENSITY: Record<ViewColumns, NoteCardDensity> = {
  1: 'list',
  2: 'grid',
  3: 'dense',
};

const REORDER_THRESHOLD_PX = 72;

interface NoteHandlers {
  open: () => void;
  longPress: () => void;
  pin: () => void;
  archive: () => void;
  restore: () => void;
  trash: () => void;
  permanentDelete: () => void;
}

type BoardItem =
  | { type: 'header'; key: string; title: string }
  | { type: 'note'; key: string; note: Note; index: number };

function buildBoardItems(
  notes: Note[],
  sortOrder: 'manual' | 'newest' | 'oldest' = 'manual',
  searchQuery = '',
): BoardItem[] {
  if (notes.length === 0) return [];

  // D14: Mid-search the order is relevance, by design. Every date heading over a relevance-ordered list is arbitrary.
  if (searchQuery.trim().length > 0) {
    return notes.map((note, index) => ({ type: 'note', key: note.id, note, index }));
  }

  const items: BoardItem[] = [];
  const firstUnpinned = notes.findIndex((note) => !note.isPinned);
  const hasPinned = notes[0]?.isPinned ?? false;

  notes.forEach((note, index) => {
    const prev = notes[index - 1];

    if (index === 0 && hasPinned) {
      items.push({ type: 'header', key: 'header-pinned', title: 'Pinned' });
    }

    if (sortOrder === 'manual') {
      // D14: "Others" only earns its place as the counterpart to "Pinned".
      if (hasPinned && index === firstUnpinned) {
        items.push({ type: 'header', key: 'header-others', title: 'Others' });
      }
    } else if (!note.isPinned) {
      // Date order (newest or oldest)
      const header = getDateHeader(note.timestamp);
      const prevHeader = prev && !prev.isPinned ? getDateHeader(prev.timestamp) : null;
      if (header !== prevHeader) {
        items.push({ type: 'header', key: `header-${header}-${index}`, title: header });
      }
    }

    items.push({ type: 'note', key: note.id, note, index });
  });

  return items;
}

export function NoteStaggeredGrid({
  notes,
  viewPreference,
  filter,
  sortOrder = 'manual',
  onNoteClick,
  onNoteLongPress,
  selectedNoteIds,
  selectionMode,
  onLabelClick,
  listActions,
  searchQuery = '',
  allowReorder = false,
  onMoveNote,
  onReorderComplete,
}: NoteStaggeredGridProps) {
  const isPhone = !useMediaQuery('(min-width: 640px)');
  const density = DENSITY[viewPreference];
  const isList = viewPreference === 1;
  const cardMin = isList ? 0 : CARD_MIN_PX[viewPreference as 2 | 3];
  const selectedSet = useMemo(() => new Set(selectedNoteIds), [selectedNoteIds]);
  // Swipe + pointer-capture fights mouse clicks on desktop — keep it phone-only.
  const swipeEnabled = isPhone && isList && Boolean(listActions) && !selectionMode;
  const canReorder =
    allowReorder && isList && !selectionMode && Boolean(onMoveNote);
  const boardItems = useMemo(
    () => buildBoardItems(notes, sortOrder, searchQuery),
    [notes, sortOrder, searchQuery],
  );

  const [draggingIndex, setDraggingIndex] = useState(-1);
  const dragOffsetRef = useRef(0);

  // A card that leaves the list — archived, trashed, restored or deleted from its quick actions —
  // took keyboard focus with it, and focus fell to <body>: a keyboard user started again from the
  // top of the page (WCAG 2.4.3). Focus moves to the card that now holds its place instead.
  const gridRef = useRef<HTMLDivElement>(null);
  const refocusIndexRef = useRef<number | null>(null);
  useEffect(() => {
    const index = refocusIndexRef.current;
    const grid = gridRef.current;
    if (index == null || index < 0 || !grid) return;
    refocusIndexRef.current = null;
    // The action failed and the card is still here, holding focus: leave it there.
    if (grid.contains(document.activeElement)) return;
    const cards = grid.querySelectorAll<HTMLElement>('[data-note-open]');
    cards[Math.min(index, cards.length - 1)]?.focus();
  }, [notes]);

  // Stable callbacks per note (A13). NoteCard is memoised, but every render handed each card new
  // closures, so the memo never held: ticking one checkbox re-rendered all 500 cards of a large
  // list, measured at 0.9 s on a 4x-throttled CPU. Each note now keeps one set of handlers for its
  // lifetime, and they call whatever the latest props are, so a card re-renders only when its own
  // note or its own state changes.
  const latest = useRef({ notes, onNoteClick, onNoteLongPress, onLabelClick, listActions });
  latest.current = { notes, onNoteClick, onNoteLongPress, onLabelClick, listActions };
  const handlerCache = useRef(new Map<string, NoteHandlers>());
  const handlersFor = (noteId: string): NoteHandlers => {
    const cached = handlerCache.current.get(noteId);
    if (cached) return cached;
    const withNote = (run: (note: Note) => void) => () => {
      const note = latest.current.notes.find((entry) => entry.id === noteId);
      if (note) run(note);
    };
    // A card that leaves the list takes focus with it; see refocusIndexRef.
    const leavingList = (run: (note: Note) => void) =>
      withNote((note) => {
        if (gridRef.current?.contains(document.activeElement)) {
          refocusIndexRef.current = latest.current.notes.findIndex((entry) => entry.id === noteId);
        }
        run(note);
      });
    const created: NoteHandlers = {
      open: withNote((note) => latest.current.onNoteClick(note)),
      longPress: withNote((note) => latest.current.onNoteLongPress(note)),
      pin: withNote((note) => latest.current.listActions?.onPinToggle?.(note)),
      archive: leavingList((note) => latest.current.listActions?.onArchive(note)),
      restore: leavingList((note) => latest.current.listActions?.onRestore(note)),
      trash: leavingList((note) => latest.current.listActions?.onTrash(note)),
      permanentDelete: leavingList((note) => latest.current.listActions?.onPermanentDelete(note)),
    };
    handlerCache.current.set(noteId, created);
    return created;
  };
  const labelClick = useRef((name: string) => latest.current.onLabelClick?.(name)).current;

  // Quick actions are hidden in selection mode by the grid's data-selecting attribute (see
  // globals.css), not by withdrawing them from every card: that changed all 500 cards' props, so
  // ticking the first checkbox or clearing the selection still re-rendered the whole list.
  const cardProps = (note: Note) => {
    const handlers = handlersFor(note.id);
    return {
      note,
      density,
      onClick: handlers.open,
      onLongPress: handlers.longPress,
      onToggleSelect: handlers.longPress,
      onLabelClick: onLabelClick ? labelClick : undefined,
      searchQuery,
      isSelected: selectedSet.has(note.id),
      onPinToggle: listActions?.onPinToggle ? handlers.pin : undefined,
      onArchive:
        listActions?.onArchive && filter === 'active' ? handlers.archive : undefined,
      onRestore:
        listActions?.onRestore && (filter === 'archived' || filter === 'trashed')
          ? handlers.restore
          : undefined,
      onTrash:
        listActions?.onTrash && (filter === 'active' || filter === 'archived')
          ? handlers.trash
          : listActions?.onPermanentDelete && filter === 'trashed'
            ? handlers.permanentDelete
            : undefined,
      isPermanentDelete: filter === 'trashed',
    };
  };

  const renderCard = (note: Note, reorder?: NoteReorderHandleProps) => {
    const reorderProps = {
      showReorderHandle: Boolean(reorder),
      reorderHandleProps: reorder,
    };

    if (!swipeEnabled || !listActions) {
      return <NoteCard {...cardProps(note)} {...reorderProps} />;
    }

    if (filter === 'active') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          {...reorderProps}
          onArchive={handlersFor(note.id).archive}
          onTrash={handlersFor(note.id).trash}
        />
      );
    }

    if (filter === 'archived') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          {...reorderProps}
          onRestore={handlersFor(note.id).restore}
          onTrash={handlersFor(note.id).trash}
        />
      );
    }

    if (filter === 'trashed') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          {...reorderProps}
          onRestore={handlersFor(note.id).restore}
          onTrash={handlersFor(note.id).permanentDelete}
        />
      );
    }

    return <NoteCard {...cardProps(note)} {...reorderProps} />;
  };

  const handleDragStart = (index: number) => {
    setDraggingIndex(index);
    dragOffsetRef.current = 0;
  };

  const handleDragMove = (deltaY: number) => {
    if (draggingIndex < 0 || !onMoveNote) return;
    dragOffsetRef.current += deltaY;

    if (dragOffsetRef.current > REORDER_THRESHOLD_PX && draggingIndex < notes.length - 1) {
      onMoveNote(draggingIndex, draggingIndex + 1);
      setDraggingIndex((current) => current + 1);
      dragOffsetRef.current = 0;
      return;
    }

    if (dragOffsetRef.current < -REORDER_THRESHOLD_PX && draggingIndex > 0) {
      onMoveNote(draggingIndex, draggingIndex - 1);
      setDraggingIndex((current) => current - 1);
      dragOffsetRef.current = 0;
    }
  };

  const handleDragEnd = () => {
    if (draggingIndex >= 0) {
      setDraggingIndex(-1);
      dragOffsetRef.current = 0;
      onReorderComplete?.();
    }
  };

  const gapClass =
    viewPreference === 3
      ? 'gap-2'
      : isList
        ? 'gap-2.5 sm:gap-3'
        : 'gap-3.5 sm:gap-4';
  const columnGapPx = viewPreference === 3 ? 12 : 16;

  return (
    <div
      ref={gridRef}
      data-selecting={selectionMode || undefined}
      className={`w-full px-shell pb-24 pt-2 ${
        isList
          ? `mx-auto grid max-w-content grid-cols-1 ${gapClass}`
          : 'mx-auto max-w-content'
      }`}
      style={
        isList
          ? undefined
          : {
              columnWidth: cardMin,
              columnGap: columnGapPx,
            }
      }
    >
      {boardItems.map((item) => {
        if (item.type === 'header') {
          return (
            <div
              key={item.key}
              className={isList ? 'col-span-full' : 'mb-1.5 w-full break-inside-avoid'}
              style={isList ? undefined : { columnSpan: 'all' }}
            >
              <NoteSectionHeader title={item.title} />
            </div>
          );
        }

        const { note, index } = item;
        if (canReorder) {
          return (
            <ReorderRow
              key={note.id}
              index={index}
              isDragging={draggingIndex === index}
              onDragStart={handleDragStart}
              onDragMove={handleDragMove}
              onDragEnd={handleDragEnd}
              onKeyboardMove={(delta) => {
                const target = index + delta;
                if (!onMoveNote || target < 0 || target >= notes.length) return;
                onMoveNote(index, target);
                onReorderComplete?.();
              }}
              render={(reorderHandleProps) => renderCard(note, reorderHandleProps)}
            />
          );
        }

        return (
          <div
            key={note.id}
            className={`min-w-0 ${
              isList
                ? ''
                : viewPreference === 3
                  ? 'mb-2 break-inside-avoid'
                  : 'mb-3 break-inside-avoid sm:mb-3.5'
            }`}
          >
            {renderCard(note)}
          </div>
        );
      })}
    </div>
  );
}

function ReorderRow({
  index,
  isDragging,
  onDragStart,
  onDragMove,
  onDragEnd,
  onKeyboardMove,
  render,
}: {
  index: number;
  isDragging: boolean;
  onDragStart: (index: number) => void;
  onDragMove: (deltaY: number) => void;
  onDragEnd: () => void;
  onKeyboardMove: (delta: -1 | 1) => void;
  render: (reorderHandleProps: NoteReorderHandleProps) => ReactNode;
}) {
  const reorderHandleProps: NoteReorderHandleProps = {
    onPointerDown: (event) => {
      event.currentTarget.setPointerCapture(event.pointerId);
      onDragStart(index);
    },
    onPointerMove: (event) => {
      if (event.buttons === 0) return;
      onDragMove(event.movementY);
    },
    onPointerUp: onDragEnd,
    onPointerCancel: onDragEnd,
    // WCAG 2.1.1 / 2.5.7: dragging was the only way to reorder.
    onKeyDown: (event) => {
      if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
      event.preventDefault();
      onKeyboardMove(event.key === 'ArrowUp' ? -1 : 1);
    },
  };

  return (
    <div
      className={`min-w-0 transition-[opacity,transform,box-shadow] duration-150 ${
        isDragging ? 'scale-[1.02] opacity-90 shadow-lg' : ''
      }`}
    >
      {render(reorderHandleProps)}
    </div>
  );
}
