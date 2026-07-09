import { ArchiveIcon, TrashIcon } from '@/components/icons/Icons';
import { NoteCard } from '@/components/notes/NoteCard';
import type { Note } from '@/types/note';
import { useRef, useState, type ReactNode } from 'react';

const THRESHOLD = 72;
const LONG_PRESS_MS = 450;
const MOVE_CANCEL_PX = 10;

interface SwipeableNoteCardProps {
  note: Note;
  compact?: boolean;
  onClick: () => void;
  onLabelClick?: (labelName: string) => void;
  onArchive?: () => void;
  onTrash?: () => void;
  onRestore?: () => void;
  searchQuery?: string;
  isSelected?: boolean;
  onLongPress?: () => void;
  children?: ReactNode;
}

export function SwipeableNoteCard({
  note,
  compact,
  onClick,
  onLabelClick,
  onArchive,
  onTrash,
  onRestore,
  searchQuery = '',
  isSelected = false,
  onLongPress,
  children,
}: SwipeableNoteCardProps) {
  const [offset, setOffset] = useState(0);
  const startX = useRef(0);
  const dragging = useRef(false);
  const moved = useRef(false);
  const longPressTimer = useRef<number | null>(null);
  const longPressTriggered = useRef(false);

  const canSwipe = Boolean(onArchive || onTrash || onRestore);

  const clearLongPress = () => {
    if (longPressTimer.current != null) {
      window.clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  };

  const reset = () => setOffset(0);

  const onPointerDown = (event: React.PointerEvent) => {
    if (!canSwipe) return;
    dragging.current = true;
    moved.current = false;
    longPressTriggered.current = false;
    startX.current = event.clientX;
    clearLongPress();
    if (onLongPress) {
      longPressTimer.current = window.setTimeout(() => {
        if (!moved.current) {
          longPressTriggered.current = true;
          dragging.current = false;
          reset();
          onLongPress();
        }
      }, LONG_PRESS_MS);
    }
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  };

  const onPointerMove = (event: React.PointerEvent) => {
    if (!dragging.current) return;
    const delta = event.clientX - startX.current;
    if (Math.abs(delta) > MOVE_CANCEL_PX) {
      moved.current = true;
      clearLongPress();
    }
    const max = 96;
    setOffset(Math.max(-max, Math.min(max, delta)));
  };

  const onPointerUp = () => {
    clearLongPress();
    if (!dragging.current) return;
    dragging.current = false;
    if (longPressTriggered.current) {
      longPressTriggered.current = false;
      reset();
      return;
    }
    if (offset > THRESHOLD) {
      if (onRestore) onRestore();
      else onArchive?.();
      reset();
      return;
    }
    if (offset < -THRESHOLD) {
      onTrash?.();
      reset();
      return;
    }
    reset();
  };

  const leftAction = onRestore ? 'Restore' : 'Archive';
  const leftIcon = onRestore ? null : <ArchiveIcon size={20} />;
  const leftColor = onRestore ? 'bg-emerald-900/80' : 'bg-amber-900/70';

  const card =
    children ??
    (
      <NoteCard
        note={note}
        compact={compact}
        onClick={onClick}
        onLabelClick={onLabelClick}
        searchQuery={searchQuery}
        isSelected={isSelected}
        onLongPress={onLongPress}
      />
    );

  if (!canSwipe) {
    return <div className="relative overflow-hidden rounded-note">{card}</div>;
  }

  return (
    <div className="relative overflow-hidden rounded-note">
      <div
        className={`absolute inset-y-0 left-0 flex w-24 items-center justify-center gap-1 text-sm font-semibold text-white ${leftColor}`}
        aria-hidden
      >
        {leftIcon}
        {leftAction}
      </div>
      <div
        className="absolute inset-y-0 right-0 flex w-24 items-center justify-center gap-1 bg-red-900/70 text-sm font-semibold text-white"
        aria-hidden
      >
        <TrashIcon size={20} />
        {onRestore ? 'Delete' : 'Trash'}
      </div>

      <div
        className="relative touch-pan-y"
        style={{
          transform: `translateX(${offset}px)`,
          transition: dragging.current ? 'none' : 'transform 150ms ease-out',
        }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        {card}
      </div>
    </div>
  );
}
