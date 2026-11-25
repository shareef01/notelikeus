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
  docked?: boolean;
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
  onMoveNote?: (fromIndex: number, toIndex: number) => boolean | void;
  onReorderComplete?: () => void;
  /**
   * One step up or down, announced: the cards' Move up / Move down actions and the reorder
   * handle's arrow keys. Offered on every layout while `allowReorder` holds; the drag handle
   * stays list-only, where a vertical drag means one direction.
   */
  onMoveNoteBy?: (noteId: string, delta: -1 | 1) => void;
}

/**
 * Grid/dense pack with CSS columns (masonry-like). List is a single readable column.
 * Column widths differ enough that List / Grid / Compact feel like real size options.
 *
 * Comfortable and compact views retain readable card widths instead of squeezing
 * five narrow columns into a desktop board. Loading uses the same column metrics.
 */
export const CARD_MIN_PX: Record<2 | 3, number> = {
  /** Comfortable Keep-style cards */
  2: 320,
  /** Compact multi-column board */
  3: 260,
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
  moveUp: () => void;
  moveDown: () => void;
}

type BoardItem =
  | { type: 'header'; key: string; title: string }
  | { type: 'note'; key: string; note: Note; index: number };

function buildBoardItems(
  notes: Note[],
  sortOrder: 'manual' | 'newest' | 'oldest' = 'manual',
  searchQuery = '',
  groupByDate = true,
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

    if (sortOrder === 'manual' || !groupByDate) {
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

const INITIAL_RENDER_COUNT = 80;
const CHUNK_RENDER_COUNT = 60;
const WINDOWED_BOARD_THRESHOLD = 1_500;
const WINDOWED_SECTION_SIZE = 80;
const WINDOWED_HANDLER_CACHE_LIMIT = WINDOWED_SECTION_SIZE * 6;

const CARD_CONTENT_VISIBILITY_STYLE: Record<ViewColumns, React.CSSProperties> = {
  1: { contentVisibility: 'auto', containIntrinsicSize: 'auto 110px' },
  2: { contentVisibility: 'auto', containIntrinsicSize: 'auto 180px' },
  3: { contentVisibility: 'auto', containIntrinsicSize: 'auto 140px' },
};

export function NoteStaggeredGrid({
  notes,
  viewPreference,
  docked = false,
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
  onMoveNoteBy,
}: NoteStaggeredGridProps) {
  const isPhone = !useMediaQuery('(min-width: 640px)');
  const isWideScreen = useMediaQuery('(min-width: 1200px)');
  const density = DENSITY[viewPreference];
  const isList = viewPreference === 1;
  // Card timestamps already give the date. On a wide board, date headers would
  // force a separate row for even one note. Keep them in narrow panes and List.
  const groupByDate = isList || docked || !isWideScreen;
  const cardMin = isList ? 0 : CARD_MIN_PX[viewPreference as 2 | 3];
  const selectedSet = useMemo(() => new Set(selectedNoteIds), [selectedNoteIds]);
  // Swipe + pointer-capture fights mouse clicks on desktop — keep it phone-only.
  const swipeEnabled = isPhone && isList && Boolean(listActions) && !selectionMode;
  const canReorder =
    allowReorder && isList && !selectionMode && Boolean(onMoveNote);
  const canMoveByStep = allowReorder && Boolean(onMoveNoteBy);
  const boardItems = useMemo(
    () => buildBoardItems(notes, sortOrder, searchQuery, groupByDate),
    [notes, sortOrder, searchQuery, groupByDate],
  );
  // Section boundaries change how CSS columns balance. Use them on large collections.
  const windowBoard = boardItems.length > WINDOWED_BOARD_THRESHOLD
    && typeof IntersectionObserver !== 'undefined';

  const queryKey = `${filter}:${searchQuery}:${sortOrder}:${viewPreference}`;
  const [prevQueryKey, setPrevQueryKey] = useState(queryKey);
  const [visibleCount, setVisibleCount] = useState(INITIAL_RENDER_COUNT);
  const sentinelRef = useRef<HTMLDivElement>(null);

  if (prevQueryKey !== queryKey) {
    setPrevQueryKey(queryKey);
    setVisibleCount(INITIAL_RENDER_COUNT);
  }

  useEffect(() => {
    setVisibleCount((prev) =>
      boardItems.length > 0 ? Math.min(prev, boardItems.length) : INITIAL_RENDER_COUNT,
    );
  }, [boardItems.length]);

  useEffect(() => {
    if (visibleCount >= boardItems.length) return undefined;
    const sentinel = sentinelRef.current;
    if (!sentinel || typeof IntersectionObserver === 'undefined') {
      setVisibleCount(boardItems.length);
      return undefined;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setVisibleCount((prev) => Math.min(prev + CHUNK_RENDER_COUNT, boardItems.length));
        }
      },
      { rootMargin: '600px' },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [visibleCount, boardItems.length]);

  const [draggingIndex, setDraggingIndex] = useState(-1);
  const dragRef = useRef<{ pointerId: number; index: number; lastY: number; offset: number } | null>(null);

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
    const cards = Array.from(grid.querySelectorAll<HTMLElement>('[data-note-open]'));
    const next = windowBoard
      ? cards.find((card) => Number(card.closest<HTMLElement>('[data-board-index]')?.dataset.boardIndex) >= index)
        ?? cards.at(-1)
      : cards[Math.min(index, cards.length - 1)];
    next?.focus();
  }, [notes, windowBoard]);

  // Stable callbacks per note (A13). NoteCard is memoised, but every render handed each card new
  // closures, so the memo never held: ticking one checkbox re-rendered all 500 cards of a large
  // list, measured at 0.9 s on a 4x-throttled CPU. Each note now keeps one set of handlers for its
  // lifetime, and they call whatever the latest props are, so a card re-renders only when its own
  // note or its own state changes.
  const latest = useRef({ notes, onNoteClick, onNoteLongPress, onLabelClick, listActions, onMoveNoteBy });
  latest.current = { notes, onNoteClick, onNoteLongPress, onLabelClick, listActions, onMoveNoteBy };
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
      moveUp: () => latest.current.onMoveNoteBy?.(noteId, -1),
      moveDown: () => latest.current.onMoveNoteBy?.(noteId, 1),
    };
    const cache = handlerCache.current;
    if (windowBoard) {
      for (const oldest of cache.keys()) {
        if (cache.size < WINDOWED_HANDLER_CACHE_LIMIT) break;
        cache.delete(oldest);
      }
    }
    cache.set(noteId, created);
    return created;
  };
  const labelClick = useRef((name: string) => latest.current.onLabelClick?.(name)).current;

  // A note that leaves the list drops its handlers, or the cache would keep one set for every note
  // ever shown (audit T-04). Its card has unmounted, so one that comes back gets a fresh set.
  useEffect(() => {
    const ids = new Set(notes.map((note) => note.id));
    for (const id of handlerCache.current.keys()) {
      if (!ids.has(id)) handlerCache.current.delete(id);
    }
  }, [notes]);

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
      onMoveUp: canMoveByStep ? handlers.moveUp : undefined,
      onMoveDown: canMoveByStep ? handlers.moveDown : undefined,
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

  const handleDragStart = (index: number, event: React.PointerEvent<HTMLButtonElement>) => {
    const grid = gridRef.current;
    if (event.button !== 0 || !grid || dragRef.current) return;
    event.stopPropagation();
    // A card can remount in another section during a move. The board owns the pointer capture.
    grid.setPointerCapture(event.pointerId);
    dragRef.current = { pointerId: event.pointerId, index, lastY: event.clientY, offset: 0 };
    setDraggingIndex(index);
  };

  const handleDragMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId || event.buttons === 0 || !onMoveNote) return;
    drag.offset += event.clientY - drag.lastY;
    drag.lastY = event.clientY;
    if (Math.abs(drag.offset) <= REORDER_THRESHOLD_PX) return;
    const next = drag.index + Math.sign(drag.offset);
    drag.offset = 0;
    if (next < 0 || next >= notes.length || onMoveNote(drag.index, next) === false) return;
    drag.index = next;
    setDraggingIndex(next);
  };

  const handleDragEnd = (event: React.PointerEvent<HTMLDivElement>) => {
    if (dragRef.current?.pointerId !== event.pointerId) return;
    dragRef.current = null;
    setDraggingIndex(-1);
    onReorderComplete?.();
  };

  const visibleItems =
    boardItems.length > INITIAL_RENDER_COUNT ? boardItems.slice(0, visibleCount) : boardItems;

  const renderItem = (item: BoardItem) => {
    if (item.type === 'header') {
      return (
        // Space above every group but the first. The header used to carry `first:pt-0`, but it
        // is always its wrapper's first child, so no header had space above it and "Today" sat
        // against the last pinned card.
        <div
          key={item.key}
          className={`[&:not(:first-child)]:pt-2 ${
            isList ? 'col-span-full' : 'mb-2 w-full break-inside-avoid'
          }`}
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
          noteId={note.id}
          boardIndex={windowBoard ? index : undefined}
          isDragging={draggingIndex === index}
          onDragStart={(event) => handleDragStart(index, event)}
          onKeyboardMove={(delta) => {
            if (onMoveNoteBy) {
              onMoveNoteBy(note.id, delta);
              return;
            }
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
        data-board-index={windowBoard ? index : undefined}
        className={`min-w-0 ${isList ? '' : 'mb-4 break-inside-avoid'}`}
        style={windowBoard ? undefined : CARD_CONTENT_VISIBILITY_STYLE[viewPreference]}
      >
        {renderCard(note)}
      </div>
    );
  };

  const columnStyle = { columnWidth: cardMin, columnGap: 16 };
  const listLayout = 'grid grid-cols-1 gap-2.5 sm:gap-3';
  return (
    <div
      ref={gridRef}
      onPointerMove={handleDragMove}
      onPointerUp={handleDragEnd}
      onPointerCancel={handleDragEnd}
      onLostPointerCapture={handleDragEnd}
      data-selecting={selectionMode || undefined}
      className={`w-full px-shell pb-24 pt-2 ${
        isList
          ? `mx-auto max-w-content ${windowBoard ? 'flex flex-col gap-2.5 sm:gap-3' : listLayout}`
          : 'mx-auto max-w-board'
      }`}
      style={isList || windowBoard ? undefined : columnStyle}
    >
      {windowBoard
        ? Array.from({ length: Math.ceil(visibleItems.length / WINDOWED_SECTION_SIZE) }, (_, section) => {
            const start = section * WINDOWED_SECTION_SIZE;
            return (
              <WindowedBoardSection
                key={`${queryKey}:${start}`}
                className={isList ? listLayout : 'flow-root'}
                style={isList ? undefined : columnStyle}
              >
                {() => visibleItems.slice(start, start + WINDOWED_SECTION_SIZE).map(renderItem)}
              </WindowedBoardSection>
            );
          })
        : visibleItems.map(renderItem)}
      {visibleCount < boardItems.length ? (
        <div
          ref={sentinelRef}
          data-sentinel="notes-grid"
          className={isList ? 'col-span-full h-8 w-full' : 'h-8 w-full break-inside-avoid'}
          style={isList ? undefined : { columnSpan: 'all' }}
          aria-hidden="true"
        />
      ) : null}
    </div>
  );
}

/** Keep the measured height when a section leaves the viewport so the scroll position stays put. */
function WindowedBoardSection({
  children,
  className,
  style,
}: {
  children: () => ReactNode;
  className: string;
  style?: React.CSSProperties;
}) {
  const elementRef = useRef<HTMLDivElement>(null);
  const [placeholderHeight, setPlaceholderHeight] = useState<number | null>(null);
  const [focusWithin, setFocusWithin] = useState(false);
  const nearby = useRef(true);

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry) return;
      nearby.current = entry.isIntersecting;
      setFocusWithin(element.contains(document.activeElement));
      setPlaceholderHeight(entry.isIntersecting ? null : element.getBoundingClientRect().height);
    }, { rootMargin: '1200px' });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const showCards = placeholderHeight == null || focusWithin;
  return (
    <div
      ref={elementRef}
      className={className}
      style={showCards ? style : { height: placeholderHeight }}
      onFocus={() => setFocusWithin(true)}
      onBlur={(event) => {
        if (event.currentTarget.contains(event.relatedTarget)) return;
        setFocusWithin(false);
        if (!nearby.current) setPlaceholderHeight(event.currentTarget.getBoundingClientRect().height);
      }}
    >
      {showCards ? children() : null}
    </div>
  );
}

function ReorderRow({
  noteId,
  boardIndex,
  isDragging,
  onDragStart,
  onKeyboardMove,
  render,
}: {
  noteId: string;
  boardIndex?: number;
  isDragging: boolean;
  onDragStart: NoteReorderHandleProps['onPointerDown'];
  onKeyboardMove: (delta: -1 | 1) => void;
  render: (reorderHandleProps: NoteReorderHandleProps) => ReactNode;
}) {
  const reorderHandleProps: NoteReorderHandleProps = {
    onPointerDown: onDragStart,
    // WCAG 2.1.1 / 2.5.7: dragging was the only way to reorder.
    onKeyDown: (event) => {
      if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
      event.preventDefault();
      const handle = event.currentTarget;
      onKeyboardMove(event.key === 'ArrowUp' ? -1 : 1);
      if (boardIndex === undefined) return;
      requestAnimationFrame(() => {
        if (handle.isConnected || document.activeElement !== document.body) return;
        const row = document.querySelector(`[data-reorder-note="${CSS.escape(noteId)}"]`);
        row?.querySelector<HTMLButtonElement>('button[aria-label="Reorder note"]')?.focus();
      });
    },
  };

  return (
    <div
      data-board-index={boardIndex}
      data-reorder-note={boardIndex === undefined ? undefined : noteId}
      className={`min-w-0 transition-[opacity,transform,box-shadow] duration-150 ${
        isDragging ? 'scale-[1.02] opacity-90 shadow-lg' : ''
      }`}
    >
      {render(reorderHandleProps)}
    </div>
  );
}
