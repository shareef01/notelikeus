import { ArchiveIcon, TrashIcon } from '@/components/icons/Icons';
import {
  NoteCard,
  type NoteCardDensity,
  type NoteReorderHandleProps,
} from '@/components/notes/NoteCard';
import type { Note } from '@/types/note';
import { memo, useRef, useState, type ReactNode } from 'react';

const THRESHOLD = 72;
const LONG_PRESS_MS = 450;
const MOVE_CANCEL_PX = 10;

interface SwipeableNoteCardProps {
  note: Note;
  compact?: boolean;
  density?: NoteCardDensity;
  onClick: () => void;
  onLabelClick?: (labelName: string) => void;
  onArchive?: () => void;
  onTrash?: () => void;
  onRestore?: () => void;
  searchQuery?: string;
  isSelected?: boolean;
  onLongPress?: () => void;
  children?: ReactNode;
  showReorderHandle?: boolean;
  reorderHandleProps?: NoteReorderHandleProps;
}

function SwipeableNoteCardImpl({
  note,
  compact,
  density,
  onClick,
  onLabelClick,
  onArchive,
  onTrash,
  onRestore,
  searchQuery = '',
  isSelected = false,
  onLongPress,
  children,
  showReorderHandle = false,
  reorderHandleProps,
}: SwipeableNoteCardProps) {
  const [offset, setOffset] = useState(0);
  const startX = useRef(0);
  const dragging = useRef(false);
  const moved = useRef(false);
  const longPressTimer = useRef<number | null>(null);
  const longPressTriggered = useRef(false);
  const pointerTypeRef = useRef<string>('mouse');

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
    pointerTypeRef.current = event.pointerType;
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
    // Capture is needed for touch so the swipe continues if the finger leaves the card.
    // On mouse it steals the subsequent click from the nested NoteCard — skip it.
    if (event.pointerType !== 'mouse') {
      (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    }
  };

  const onPointerMove = (event: React.PointerEvent) => {
    if (!dragging.current) return;
    const delta = event.clientX - startX.current;
    if (!moved.current && Math.abs(delta) > MOVE_CANCEL_PX) {
      moved.current = true;
      clearLongPress();
    }
    // Below the cancel threshold this is a click/tap, not a swipe — don't shift the card
    // at all, or ordinary mouse/trackpad jitter during a click would visibly slide it and
    // peek the archive/trash background out from behind it.
    if (!moved.current) return;
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
    const wasTap = !moved.current;
    reset();
    // Touch + pointer-capture often prevents the nested article click — open on tap.
    // Mouse keeps the normal NoteCard click path.
    if (wasTap && pointerTypeRef.current !== 'mouse') onClick();
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
        density={density}
        onClick={onClick}
        onLabelClick={onLabelClick}
        searchQuery={searchQuery}
        isSelected={isSelected}
        // When canSwipe, this wrapper already runs its own long-press timer via
        // onPointerDown/Up below and calls onLongPress() itself — also passing it to
        // NoteCard would run a second, independent useLongPress on the nested <article>.
        // Its pointerup/cancel/leave never fire once this wrapper's setPointerCapture(
        // below) takes over the pointer, so that inner timer never gets cleared and fires
        // late on ordinary clicks, spuriously triggering long-press (selection mode).
        onLongPress={canSwipe ? undefined : onLongPress}
        showReorderHandle={showReorderHandle}
        reorderHandleProps={reorderHandleProps}
      />
    );

  if (!canSwipe) {
    return <div className="relative overflow-hidden rounded-note">{card}</div>;
  }

  // Only in the DOM while an actual drag is in progress (offset !== 0) — these must never
  // rely purely on the card sitting exactly at translateX(0) to stay hidden, since any
  // transient state (a snap-back frame, a stray offset) would otherwise expose them.
  const revealingLeft = offset > 0;
  const revealingRight = offset < 0;

  return (
    <div className="relative overflow-hidden rounded-note">
      {revealingLeft ? (
        <div
          className={`absolute inset-y-0 left-0 flex w-24 items-center justify-center gap-1 text-sm font-semibold text-white ${leftColor}`}
          aria-hidden
        >
          {leftIcon}
          {leftAction}
        </div>
      ) : null}
      {revealingRight ? (
        <div
          className="absolute inset-y-0 right-0 flex w-24 items-center justify-center gap-1 bg-red-900/70 text-sm font-semibold text-white"
          aria-hidden
        >
          <TrashIcon size={20} />
          {onRestore ? 'Delete' : 'Trash'}
        </div>
      ) : null}

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

/**
 * onClick/onArchive/onTrash/onRestore/onLongPress are excluded deliberately: NoteStaggeredGrid
 * recreates them on every render, but they always close over this same `note` prop — so as long
 * as `note` (and the other rendered props) haven't changed, a "stale" closure behaves identically
 * to a fresh one, and skipping the re-render is safe.
 */
function swipeableNoteCardPropsAreEqual(
  prev: SwipeableNoteCardProps,
  next: SwipeableNoteCardProps,
): boolean {
  return (
    prev.note === next.note &&
    prev.compact === next.compact &&
    prev.density === next.density &&
    prev.searchQuery === next.searchQuery &&
    prev.isSelected === next.isSelected &&
    prev.showReorderHandle === next.showReorderHandle &&
    prev.reorderHandleProps === next.reorderHandleProps &&
    prev.onLabelClick === next.onLabelClick &&
    prev.children === next.children &&
    Boolean(prev.onArchive) === Boolean(next.onArchive) &&
    Boolean(prev.onTrash) === Boolean(next.onTrash) &&
    Boolean(prev.onRestore) === Boolean(next.onRestore)
  );
}

export const SwipeableNoteCard = memo(SwipeableNoteCardImpl, swipeableNoteCardPropsAreEqual);
