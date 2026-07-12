import { PinIcon, DragHandleIcon, BellIcon } from '@/components/icons/Icons';
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
  const title = note.isLocked ? 'Locked note' : note.title || 'Untitled';
  const showBody = !note.isLocked && note.content.length > 0;
  const previewBody = stripMarkdownForPreview(note.content);
  const highlight = (text: string) => highlightSearchText(text, searchQuery);
  const hasReminder =
    note.reminderTimestamp != null && note.reminderTimestamp > Date.now() && !note.isTrashed;

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
      className={`relative w-full cursor-pointer rounded-note text-left shadow-sm transition-all duration-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary/60 border border-white/[0.03] ${
        showReorderHandle ? 'pl-12 pr-4 py-4 sm:pr-5 sm:py-5' : 'p-4 sm:p-5'
      } ${
        isSelected
          ? 'ring-1 ring-brand-primary/50 bg-brand-primary/[0.03] scale-[0.985] shadow-inner'
          : 'hover:bg-white/[0.02] hover:border-white/[0.08] hover:shadow-lg active:scale-[0.995]'
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
      <div className="flex items-start justify-between gap-2">
        <h2
          className={`font-semibold tracking-[-0.4px] ${
            compact ? 'line-clamp-1 text-[15px]' : 'text-[16px] leading-[22px] sm:text-[17px] sm:leading-[24px] line-clamp-2'
          }`}
        >
          {highlight(title)}
        </h2>
        {note.isPinned ? (
          <PinIcon className="mt-1 shrink-0 opacity-50" size={15} />
        ) : hasReminder ? (
          <span className="mt-1 shrink-0 opacity-60" aria-label="Reminder set">
            <BellIcon size={15} />
          </span>
        ) : null}
      </div>

      {showBody ? (
        <p
          className={`mt-1.5 text-[13px] leading-[1.45em] opacity-70 sm:mt-2 sm:text-[14px] sm:leading-[1.5em] ${
            compact ? 'line-clamp-2' : 'sm:line-clamp-5 line-clamp-3'
          }`}
        >
          {highlight(previewBody)}
        </p>
      ) : null}

      {!note.isLocked && note.checklist.length > 0 ? (
        <p className="mt-2.5 text-[11px] font-bold tracking-wide opacity-50">
          {note.checklist.filter((item) => item.isChecked).length}/{note.checklist.length} CHECKED
        </p>
      ) : null}

      {!compact && note.labels.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-1.5 sm:mt-3.5">
          {note.labels.slice(0, 2).map((label) =>
            onLabelClick ? (
              <button
                key={label.id}
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  onLabelClick(label.name);
                }}
                className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest opacity-60 hover:opacity-100 transition-opacity"
                style={{ backgroundColor: 'rgba(255,255,255,0.06)' }}
              >
                {label.name}
              </button>
            ) : (
              <span
                key={label.id}
                className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest opacity-60"
                style={{ backgroundColor: 'rgba(255,255,255,0.06)' }}
              >
                {label.name}
              </span>
            ),
          )}
          {note.labels.length > 2 ? (
            <span className="text-[9px] font-semibold uppercase tracking-wider opacity-40">
              +{note.labels.length - 2}
            </span>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}
