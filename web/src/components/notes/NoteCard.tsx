import {
  CheckCircleIcon,
  CheckCircleOutlineIcon,
  DragHandleIcon,
  LockIcon,
  NotificationIcon,
  PinIcon,
} from '@/components/icons/Icons';
import { useLongPress } from '@/hooks/useLongPress';
import { formatListTimestamp } from '@/lib/text/dateTime';
import { highlightSearchText } from '@/lib/text/highlightSearch';
import { stripMarkdownForPreview } from '@/lib/text/markdown';
import type { Note } from '@/types/note';
import { noteSurfaceStyle } from '@/theme/contrast';
import { argbToCssAlpha } from '@/theme/colors';
import { memo, type PointerEventHandler, type ReactNode } from 'react';

export interface NoteReorderHandleProps {
  onPointerDown: PointerEventHandler<HTMLButtonElement>;
  onPointerMove: PointerEventHandler<HTMLButtonElement>;
  onPointerUp: PointerEventHandler<HTMLButtonElement>;
  onPointerCancel: PointerEventHandler<HTMLButtonElement>;
}

export type NoteCardDensity = 'list' | 'grid' | 'dense';

interface NoteCardProps {
  note: Note;
  onClick: () => void;
  /** @deprecated use density */
  compact?: boolean;
  density?: NoteCardDensity;
  onLabelClick?: (labelName: string) => void;
  searchQuery?: string;
  isSelected?: boolean;
  onLongPress?: () => void;
  showReorderHandle?: boolean;
  reorderHandleProps?: NoteReorderHandleProps;
}

function NoteCardImpl({
  note,
  onClick,
  compact = false,
  density: densityProp,
  onLabelClick,
  searchQuery = '',
  isSelected = false,
  onLongPress,
  showReorderHandle = false,
  reorderHandleProps,
}: NoteCardProps) {
  const density: NoteCardDensity = densityProp ?? (compact ? 'grid' : 'list');
  const isList = density === 'list';
  const isDense = density === 'dense';

  const surface = noteSurfaceStyle(note.color);
  const contentColor = note.color === 0 ? 'rgb(var(--primary-rgb))' : surface.color;
  const labelChipStyle =
    note.color === 0
      ? { backgroundColor: 'rgba(255,255,255,0.12)', color: contentColor }
      : { backgroundColor: argbToCssAlpha(note.color, 0.1), color: contentColor };
  const title = note.isLocked ? 'Hidden note' : note.title || 'Untitled';
  const showBody = !note.isLocked && note.content.length > 0;
  const previewBody = stripMarkdownForPreview(note.content);
  const highlight = (text: string) => highlightSearchText(text, searchQuery);
  const hasReminder =
    note.reminderTimestamp != null && note.reminderTimestamp > Date.now() && !note.isTrashed;
  const showStatusCluster = !isSelected && (note.isPinned || hasReminder || note.isLocked);
  const checkedCount = note.checklist.filter((item) => item.isChecked).length;
  const showChecklist = !note.isLocked && note.checklist.length > 0;
  const showLabels = !note.isLocked && note.labels.length > 0;
  const labelLimit = isDense ? 1 : isList ? 3 : 2;
  const timeLabel = formatListTimestamp(note.timestamp);

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
    note.isLocked ? 'Hidden note' : null,
    isSelected ? 'Selected' : null,
  ].filter(Boolean);

  const statusIcons = (size: number): ReactNode => {
    if (isSelected) {
      return (
        <div
          className="flex size-6 shrink-0 items-center justify-center rounded-full bg-brand-primary text-[11px] font-bold text-true-surface"
          aria-hidden
        >
          ✓
        </div>
      );
    }
    if (!showStatusCluster) return null;
    return (
      <div className="flex shrink-0 items-center gap-1.5 opacity-50" aria-hidden>
        {note.isPinned ? <PinIcon size={size} /> : null}
        {hasReminder ? <NotificationIcon size={size} /> : null}
        {note.isLocked ? <LockIcon size={size} /> : null}
      </div>
    );
  };

  const labelChips = showLabels ? (
    <div className={`flex flex-wrap gap-1.5 ${isList ? 'mt-2' : 'mt-3'}`}>
      {note.labels.slice(0, labelLimit).map((label) =>
        onLabelClick ? (
          <button
            key={label.id}
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              onLabelClick(label.name);
            }}
            className={`rounded-full font-semibold uppercase tracking-wider hover:opacity-80 ${
              isDense ? 'px-1.5 py-px text-[9px]' : 'px-2 py-0.5 text-[10px]'
            }`}
            style={labelChipStyle}
          >
            {label.name}
          </button>
        ) : (
          <span
            key={label.id}
            className={`rounded-full font-semibold uppercase tracking-wider ${
              isDense ? 'px-1.5 py-px text-[9px]' : 'px-2 py-0.5 text-[10px]'
            }`}
            style={labelChipStyle}
          >
            {label.name}
          </span>
        ),
      )}
      {note.labels.length > labelLimit ? (
        <span className="self-center text-[10px] font-semibold uppercase tracking-wider opacity-65">
          +{note.labels.length - labelLimit}
        </span>
      ) : null}
    </div>
  ) : null;

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
      className={`relative flex w-full cursor-pointer overflow-hidden rounded-note text-left shadow-sm transition-[transform,box-shadow,background-color] duration-200 ${
        isList
          ? 'h-full flex-row items-stretch gap-0'
          : isDense
            ? 'flex-col p-3'
            : 'flex-col p-4'
      } ${showReorderHandle ? 'pl-11' : ''} ${
        isSelected
          ? 'ring-2 ring-brand-primary ring-offset-2 ring-offset-true-surface'
          : note.color === 0
            ? 'border border-brand-outline/40 hover:-translate-y-0.5 hover:border-brand-outline/70 hover:shadow-md active:translate-y-0'
            : 'hover:-translate-y-0.5 hover:shadow-md active:translate-y-0'
      }`}
      style={surface}
      aria-pressed={isSelected || undefined}
      aria-label={[title, ...statusParts].join(', ')}
    >
      {showReorderHandle && reorderHandleProps ? (
        <button
          type="button"
          aria-label="Reorder note"
          className="absolute left-0 top-1/2 z-10 flex size-10 -translate-y-1/2 cursor-grab touch-none items-center justify-center text-brand-muted/40 active:cursor-grabbing"
          {...reorderHandleProps}
        >
          <DragHandleIcon size={18} />
        </button>
      ) : null}

      {isList ? (
        <>
          <span
            className={`my-3 ml-3 w-1 shrink-0 rounded-full ${
              note.color !== 0 ? 'bg-current/30' : 'bg-brand-outline/70'
            }`}
            aria-hidden
          />

          <div className="flex min-w-0 flex-1 items-center gap-4 px-4 py-3.5 sm:px-5 sm:py-4">
            <div className="min-w-0 flex-1">
              <h2 className="line-clamp-1 text-[15px] font-semibold leading-[1.35] tracking-[-0.02em] sm:text-[16px]">
                {highlight(title)}
              </h2>
              {showBody ? (
                <p className="mt-1.5 line-clamp-2 text-[13px] leading-[1.45] opacity-[0.58]">
                  {highlight(previewBody)}
                </p>
              ) : null}
              {showChecklist ? (
                <p className="mt-2 text-[11px] font-medium tracking-wide opacity-45">
                  {checkedCount}/{note.checklist.length} checked
                </p>
              ) : null}
              {labelChips}
            </div>

            <div className="flex shrink-0 flex-col items-end justify-center gap-2 self-stretch py-0.5">
              {statusIcons(15)}
              <time
                dateTime={new Date(note.timestamp).toISOString()}
                className="text-[11px] font-medium tabular-nums tracking-wide opacity-40"
              >
                {timeLabel}
              </time>
            </div>
          </div>
        </>
      ) : (
        <>
          <div className="flex items-start gap-2">
            <h2
              className={`min-w-0 flex-1 font-semibold ${
                isDense
                  ? 'line-clamp-2 text-[13px] leading-snug tracking-[-0.01em]'
                  : 'line-clamp-2 text-[15px] leading-snug tracking-[-0.025em] sm:text-[16px]'
              }`}
            >
              {highlight(title)}
            </h2>
            {statusIcons(isDense ? 12 : 14)}
          </div>

          {showBody ? (
            <p
              className={
                isDense
                  ? 'mt-1.5 line-clamp-3 text-[11px] leading-snug opacity-65'
                  : 'mt-2 line-clamp-4 text-[13px] leading-[1.45] opacity-[0.62] sm:line-clamp-5'
              }
            >
              {highlight(previewBody)}
            </p>
          ) : null}

          {showChecklist ? (
            isDense ? (
              <p className="mt-2 text-[10px] font-medium tracking-wide opacity-45">
                {checkedCount}/{note.checklist.length} checked
              </p>
            ) : (
              <div className="mt-3 space-y-1.5">
                {note.checklist.slice(0, 3).map((item) => (
                  <div key={item.id} className="flex items-center gap-1.5">
                    {item.isChecked ? (
                      <CheckCircleIcon size={14} className="shrink-0 opacity-55" />
                    ) : (
                      <CheckCircleOutlineIcon size={14} className="shrink-0 opacity-55" />
                    )}
                    <span className="line-clamp-1 text-[12px] leading-snug opacity-55">
                      {highlight(stripMarkdownForPreview(item.text))}
                    </span>
                  </div>
                ))}
              </div>
            )
          ) : null}

          {labelChips}

          <div
            className={`mt-3 flex items-center justify-between border-t border-current/10 pt-2.5 ${
              isDense ? 'mt-2.5 pt-2' : ''
            }`}
          >
            <time
              dateTime={new Date(note.timestamp).toISOString()}
              className={`font-medium tabular-nums tracking-wide opacity-40 ${
                isDense ? 'text-[10px]' : 'text-[11px]'
              }`}
            >
              {timeLabel}
            </time>
          </div>
        </>
      )}
    </article>
  );
}

function noteCardPropsAreEqual(prev: NoteCardProps, next: NoteCardProps): boolean {
  return (
    prev.note === next.note &&
    prev.compact === next.compact &&
    prev.density === next.density &&
    prev.searchQuery === next.searchQuery &&
    prev.isSelected === next.isSelected &&
    prev.showReorderHandle === next.showReorderHandle &&
    prev.reorderHandleProps === next.reorderHandleProps &&
    prev.onLabelClick === next.onLabelClick
  );
}

export const NoteCard = memo(NoteCardImpl, noteCardPropsAreEqual);
