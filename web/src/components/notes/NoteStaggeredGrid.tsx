import { NoteCard, type NoteCardDensity, type NoteReorderHandleProps } from '@/components/notes/NoteCard';
import { NoteSectionHeader } from '@/components/notes/NoteSectionHeader';
import { SwipeableNoteCard } from '@/components/notes/SwipeableNoteCard';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { getDateHeader } from '@/lib/text/dateTime';
import type { ViewColumns } from '@/store/uiStore';
import type { Note, NoteFilter } from '@/types/note';
import { useMemo, useRef, useState, type ReactNode } from 'react';

interface NoteListActions {
  onArchive: (note: Note) => void;
  onTrash: (note: Note) => void;
  onRestore: (note: Note) => void;
  onPermanentDelete: (note: Note) => void;
}

interface NoteStaggeredGridProps {
  notes: Note[];
  /** User view preference — drives card min-width + density. */
  viewPreference: ViewColumns;
  filter: NoteFilter;
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
 * Grid/dense pack with auto-fill. List is a single readable column —
 * never multi-column ribbons that fight the “list” metaphor.
 */
const CARD_MIN_PX: Record<2 | 3, number> = {
  2: 220,
  3: 156,
};

const DENSITY: Record<ViewColumns, NoteCardDensity> = {
  1: 'list',
  2: 'grid',
  3: 'dense',
};

const REORDER_THRESHOLD_PX = 72;

type BoardItem =
  | { type: 'header'; key: string; title: string }
  | { type: 'note'; key: string; note: Note; index: number };

function buildBoardItems(notes: Note[]): BoardItem[] {
  const items: BoardItem[] = [];
  notes.forEach((note, index) => {
    const prev = notes[index - 1];

    if (note.isPinned && index === 0) {
      items.push({ type: 'header', key: 'header-pinned', title: 'Pinned' });
    }

    if (!note.isPinned) {
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
  const boardItems = useMemo(() => buildBoardItems(notes), [notes]);

  const [draggingIndex, setDraggingIndex] = useState(-1);
  const dragOffsetRef = useRef(0);

  const cardProps = (note: Note) => ({
    note,
    density,
    onClick: () => onNoteClick(note),
    onLongPress: () => onNoteLongPress(note),
    onLabelClick,
    searchQuery,
    isSelected: selectedSet.has(note.id),
  });

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
          onArchive={() => listActions.onArchive(note)}
          onTrash={() => listActions.onTrash(note)}
        />
      );
    }

    if (filter === 'archived') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          {...reorderProps}
          onRestore={() => listActions.onRestore(note)}
          onTrash={() => listActions.onTrash(note)}
        />
      );
    }

    if (filter === 'trashed') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          {...reorderProps}
          onRestore={() => listActions.onRestore(note)}
          onTrash={() => listActions.onPermanentDelete(note)}
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
      ? 'gap-2 sm:gap-2.5'
      : isList
        ? 'gap-2 sm:gap-2.5'
        : 'gap-3 sm:gap-3.5';

  return (
    <div
      className={`grid w-full ${gapClass} px-3 pb-24 pt-3 sm:px-4 lg:px-6 ${
        isList ? 'max-w-3xl grid-cols-1' : 'mx-auto max-w-content items-start'
      }`}
      style={
        isList
          ? undefined
          : {
              gridTemplateColumns: `repeat(auto-fill, minmax(min(100%, ${cardMin}px), 1fr))`,
            }
      }
    >
      {boardItems.map((item) => {
        if (item.type === 'header') {
          return (
            <div key={item.key} className="col-span-full">
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
              render={(reorderHandleProps) => renderCard(note, reorderHandleProps)}
            />
          );
        }

        return (
          <div key={note.id} className="min-w-0">
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
  render,
}: {
  index: number;
  isDragging: boolean;
  onDragStart: (index: number) => void;
  onDragMove: (deltaY: number) => void;
  onDragEnd: () => void;
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
