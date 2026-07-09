import { DragHandleIcon } from '@/components/icons/Icons';
import { NoteCard } from '@/components/notes/NoteCard';
import { NoteSectionHeader } from '@/components/notes/NoteSectionHeader';
import { SwipeableNoteCard } from '@/components/notes/SwipeableNoteCard';
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
  columns: 1 | 2 | 3;
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

const COLUMN_CLASSES: Record<1 | 2 | 3, string> = {
  1: '',
  2: 'columns-2',
  3: 'columns-3',
};

const REORDER_THRESHOLD_PX = 72;

export function NoteStaggeredGrid({
  notes,
  columns,
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
  const compact = columns > 1;
  const selectedSet = useMemo(() => new Set(selectedNoteIds), [selectedNoteIds]);
  const swipeEnabled = columns === 1 && Boolean(listActions) && !selectionMode;
  const canReorder = allowReorder && columns === 1 && !selectionMode && Boolean(onMoveNote);

  const hasPinned = notes.some((note) => note.isPinned);
  const hasUnpinned = notes.some((note) => !note.isPinned);
  const showSections = hasPinned && hasUnpinned;

  const [draggingIndex, setDraggingIndex] = useState(-1);
  const dragOffsetRef = useRef(0);

  const cardProps = (note: Note) => ({
    note,
    compact,
    onClick: () => onNoteClick(note),
    onLongPress: () => onNoteLongPress(note),
    onLabelClick,
    searchQuery,
    isSelected: selectedSet.has(note.id),
  });

  const renderCard = (note: Note) => {
    const card = <NoteCard {...cardProps(note)} />;

    if (!swipeEnabled || !listActions) {
      return card;
    }

    if (filter === 'active') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          onArchive={() => listActions.onArchive(note)}
          onTrash={() => listActions.onTrash(note)}
        />
      );
    }

    if (filter === 'archived') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          onRestore={() => listActions.onRestore(note)}
          onTrash={() => listActions.onTrash(note)}
        />
      );
    }

    if (filter === 'trashed') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          onRestore={() => listActions.onRestore(note)}
          onTrash={() => listActions.onPermanentDelete(note)}
        />
      );
    }

    return card;
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

  if (columns === 1) {
    return (
      <div className="flex flex-col gap-note-gap px-3 pb-24 pt-2 sm:px-4 lg:px-6">
        {notes.map((note, index) => {
          const showPinnedHeader = showSections && note.isPinned && index === 0;
          const showOthersHeader =
            showSections && !note.isPinned && (index === 0 || !notes[index - 1]?.isPinned);

          return (
            <div key={note.id}>
              {showPinnedHeader ? <NoteSectionHeader title="Pinned" /> : null}
              {showOthersHeader ? <NoteSectionHeader title="Others" /> : null}
              {canReorder ? (
                <ReorderRow
                  index={index}
                  isDragging={draggingIndex === index}
                  onDragStart={handleDragStart}
                  onDragMove={handleDragMove}
                  onDragEnd={handleDragEnd}
                  render={() => renderCard(note)}
                />
              ) : (
                <StaticListRow render={() => renderCard(note)} />
              )}
            </div>
          );
        })}
      </div>
    );
  }

  const pinned = notes.filter((note) => note.isPinned);
  const others = notes.filter((note) => !note.isPinned);
  const columnClass = COLUMN_CLASSES[columns];
  const renderMasonrySection = (sectionNotes: Note[]) =>
    sectionNotes.map((note) => (
      <div key={note.id} className="mb-note-gap break-inside-avoid">
        {renderCard(note)}
      </div>
    ));

  return (
    <div
      className={`${columnClass} gap-note-gap px-3 pb-24 pt-2 sm:px-4 lg:px-6`}
      style={{ columnGap: '12px' }}
    >
      {showSections ? (
        <div className="mb-note-gap break-inside-avoid">
          <NoteSectionHeader title="Pinned" />
        </div>
      ) : null}
      {renderMasonrySection(pinned)}
      {showSections ? (
        <div className="mb-note-gap break-inside-avoid">
          <NoteSectionHeader title="Others" />
        </div>
      ) : null}
      {renderMasonrySection(others)}
    </div>
  );
}

function StaticListRow({ render }: { render: () => ReactNode }) {
  return (
    <div className="flex items-center gap-0">
      <div className="hidden w-8 shrink-0 justify-center pl-2 md:flex lg:w-10 lg:pl-4" aria-hidden>
        <DragHandleIcon size={24} className="text-brand-muted/20" />
      </div>
      <div className="min-w-0 flex-1">{render()}</div>
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
  render: () => ReactNode;
}) {
  return (
    <div className={`flex items-stretch gap-1 ${isDragging ? 'opacity-90' : ''}`}>
      <button
        type="button"
        aria-label="Reorder note"
        className="flex min-h-[44px] w-11 shrink-0 cursor-grab touch-none items-center justify-center rounded-note text-brand-muted/50 active:cursor-grabbing active:bg-white/5 sm:w-12"
        onPointerDown={(event) => {
          event.currentTarget.setPointerCapture(event.pointerId);
          onDragStart(index);
        }}
        onPointerMove={(event) => {
          if (event.buttons === 0) return;
          onDragMove(event.movementY);
        }}
        onPointerUp={onDragEnd}
        onPointerCancel={onDragEnd}
      >
        <DragHandleIcon size={22} />
      </button>
      <div className="min-w-0 flex-1">{render()}</div>
    </div>
  );
}
