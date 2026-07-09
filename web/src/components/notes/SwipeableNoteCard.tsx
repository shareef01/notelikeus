import { ArchiveIcon, TrashIcon } from '@/components/icons/Icons';
import { NoteCard } from '@/components/notes/NoteCard';
import type { Note } from '@/types/note';
import { useRef, useState, type ReactNode } from 'react';

const THRESHOLD = 72;

interface SwipeableNoteCardProps {
  note: Note;
  compact?: boolean;
  onClick: () => void;
  onLabelClick?: (labelName: string) => void;
  onArchive?: () => void;
  onTrash?: () => void;
  onRestore?: () => void;
  searchQuery?: string;
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
  children,
}: SwipeableNoteCardProps) {
  const [offset, setOffset] = useState(0);
  const startX = useRef(0);
  const dragging = useRef(false);

  const canSwipe = Boolean(onArchive || onTrash || onRestore);

  const reset = () => setOffset(0);

  const onPointerDown = (event: React.PointerEvent) => {
    if (!canSwipe) return;
    dragging.current = true;
    startX.current = event.clientX;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  };

  const onPointerMove = (event: React.PointerEvent) => {
    if (!dragging.current) return;
    const delta = event.clientX - startX.current;
    const max = 96;
    setOffset(Math.max(-max, Math.min(max, delta)));
  };

  const onPointerUp = () => {
    if (!dragging.current) return;
    dragging.current = false;
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

  return (
    <div className="relative overflow-hidden rounded-note">
      {canSwipe ? (
        <>
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
        </>
      ) : null}

      <div
        className="relative touch-pan-y"
        style={{ transform: canSwipe ? `translateX(${offset}px)` : undefined, transition: dragging.current ? 'none' : 'transform 150ms ease-out' }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        {children ?? (
          <NoteCard
            note={note}
            compact={compact}
            onClick={onClick}
            onLabelClick={onLabelClick}
            searchQuery={searchQuery}
          />
        )}
      </div>
    </div>
  );
}
