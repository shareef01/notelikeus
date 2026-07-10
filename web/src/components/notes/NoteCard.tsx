import {
  CheckCircleIcon,
  CheckCircleOutlineIcon,
  DragHandleIcon,
  LockIcon,
  NotificationIcon,
  PinIcon,
} from '@/components/icons/Icons';
import { useLongPress } from '@/hooks/useLongPress';
import { highlightSearchText } from '@/lib/text/highlightSearch';
import { stripMarkdownForPreview } from '@/lib/text/markdown';
import type { Note } from '@/types/note';
import { noteSurfaceStyle } from '@/theme/contrast';
import type { PointerEventHandler } from 'react';

export interface NoteReorderHandleProps {
  onPointerDown: PointerEventHandler<HTMLButtonElement>;
  onPointerMove: PointerEventHandler<HTMLButtonElement>;
  onPointerUp: PointerEventHandler<HTMLButtonElement>;
  onPointerCancel: PointerEventHandler<HTMLButtonElement>;
}

interface NoteCardProps {
  note: Note;
  onClick: () => void;
  compact?: boolean;
  onLabelClick?: (labelName: string) => void;
  searchQuery?: string;
  isSelected?: boolean;
  onLongPress?: () => void;
  showReorderHandle?: boolean;
  reorderHandleProps?: NoteReorderHandleProps;
}

/**
 * Note Card Overhaul (Web)
 * Premium Typography: Inter 18px SemiBold Titles, -0.5px tracking.
 * Geometric Discipline: 16px radius, 16px inner padding.
 */
export function NoteCard({
  note,
  onClick,
  compact = false,
  onLabelClick,
  searchQuery = '',
  isSelected = false,
  onLongPress,
  showReorderHandle = false,
  reorderHandleProps,
}: NoteCardProps) {
  const surface = noteSurfaceStyle(note.color);
  const contentColor = note.color === 0 ? 'var(--brand-primary)' : surface.color;
  const labelChipStyle =
    note.color === 0
      ? { backgroundColor: 'rgba(255,255,255,0.12)', color: contentColor }
      : { backgroundColor: `${surface.backgroundColor}1a`, color: contentColor };
  const title = note.isLocked ? 'Locked note' : note.title || 'Untitled';
  const showBody = !note.isLocked && note.content.length > 0;
  const previewBody = stripMarkdownForPreview(note.content);
  const highlight = (text: string) => highlightSearchText(text, searchQuery);
  const hasReminder =
    note.reminderTimestamp != null && note.reminderTimestamp > Date.now() && !note.isTrashed;
  const showStatusCluster = !isSelected && (note.isPinned || hasReminder || note.isLocked);

  const { longPressProps, shouldSuppressClick } = useLongPress({
    onLongPress: () => onLongPress?.(),
  });

  const handleClick = () => {
    if (shouldSuppressClick()) return;
    onClick();
  };

  const statusParts = [
    note.isPinned ? 'Pinned' : null,
    hasReminder ? 'Reminder set' : null,
    note.isLocked ? 'Locked note' : null,
    isSelected ? 'Selected' : null,
  ].filter(Boolean);

  return (
    <article
      role="button"
      tabIndex={0}
      onClick={handleClick}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          handleClick();
        }
      }}
      {...(onLongPress ? longPressProps : {})}
      className={`relative w-full cursor-pointer rounded-note text-left shadow-sm transition-all duration-200 ${
        showReorderHandle ? 'pl-12 pr-layout-gap py-layout-gap' : 'p-layout-gap'
      } ${
        isSelected
          ? 'ring-2 ring-brand-primary ring-offset-2 ring-offset-true-black'
          : note.color === 0
            ? 'border border-brand-outline/45 hover:scale-[1.02] hover:shadow-md active:scale-[0.98]'
            : 'hover:scale-[1.02] hover:shadow-md active:scale-[0.98]'
      }`}
      style={surface}
      aria-pressed={isSelected || undefined}
      aria-label={[title, ...statusParts].join(', ')}
    >
      {showReorderHandle && reorderHandleProps ? (
        <button
          type="button"
          aria-label="Reorder note"
          className="absolute left-0 top-1/2 flex size-11 -translate-y-1/2 cursor-grab touch-none items-center justify-center text-brand-muted/40 active:cursor-grabbing"
          {...reorderHandleProps}
        >
          <DragHandleIcon size={20} />
        </button>
      ) : null}
      {isSelected ? (
        <div
          className="absolute right-3 top-3 flex size-6 items-center justify-center rounded-full bg-brand-primary text-xs font-bold text-true-black"
          aria-hidden
        >
          ✓
        </div>
      ) : showStatusCluster ? (
        <div
          className="absolute right-3 top-3 flex items-center gap-1"
          aria-hidden
        >
          {note.isPinned ? (
            <PinIcon size={14} className="opacity-55" />
          ) : null}
          {hasReminder ? (
            <NotificationIcon size={14} className="opacity-55" />
          ) : null}
          {note.isLocked ? (
            <LockIcon size={14} className="opacity-50" />
          ) : null}
        </div>
      ) : null}

      <div className="flex items-start justify-between gap-3">
        <h2
          className={`font-bold tracking-[-0.5px] ${
            compact ? 'line-clamp-1 text-base' : 'text-[18px] leading-[25px] line-clamp-2'
          } ${showStatusCluster && !compact ? 'pr-10' : ''}`}
        >
          {highlight(title)}
        </h2>
      </div>

      {showBody ? (
        <p
          className={`mt-2.5 text-[14px] leading-[1.4em] opacity-85 ${
            compact ? 'line-clamp-2' : 'line-clamp-6'
          }`}
        >
          {highlight(previewBody)}
        </p>
      ) : null}

      {!note.isLocked && note.checklist.length > 0 ? (
        compact ? (
          <p className="mt-3 text-[12px] font-bold opacity-65">
            {note.checklist.filter((item) => item.isChecked).length}/{note.checklist.length} CHECKED
          </p>
        ) : (
          <div className="mt-2 space-y-1.5">
            {note.checklist.slice(0, 3).map((item) => (
              <div key={item.id} className="flex items-center gap-1.5">
                {item.isChecked ? (
                  <CheckCircleIcon size={16} className="shrink-0 opacity-60" />
                ) : (
                  <CheckCircleOutlineIcon size={16} className="shrink-0 opacity-60" />
                )}
                <span className="line-clamp-1 text-[12px] leading-[1.4em] opacity-60">
                  {highlight(stripMarkdownForPreview(item.text))}
                </span>
              </div>
            ))}
          </div>
        )
      ) : null}

      {!compact && note.labels.length > 0 ? (
        <div className="mt-4 flex flex-wrap gap-1.5">
          {note.labels.slice(0, 2).map((label) =>
            onLabelClick ? (
              <button
                key={label.id}
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  onLabelClick(label.name);
                }}
                className="rounded-full px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wider hover:opacity-80"
                style={labelChipStyle}
              >
                {label.name}
              </button>
            ) : (
              <span
                key={label.id}
                className="rounded-full px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wider"
                style={labelChipStyle}
              >
                {label.name}
              </span>
            ),
          )}
          {note.labels.length > 2 ? (
            <span className="text-[10px] font-semibold uppercase tracking-wider opacity-65">
              +{note.labels.length - 2}
            </span>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}
