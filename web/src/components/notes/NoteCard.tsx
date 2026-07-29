import {
  CheckCircleIcon,
  CheckCircleOutlineIcon,
  DragHandleIcon,
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
  const title = note.title || 'Untitled';
  const showBody = note.content.length > 0;
  const previewBody = stripMarkdownForPreview(note.content);
  const highlight = (text: string) => highlightSearchText(text, searchQuery);
  const hasReminder =
    note.reminderTimestamp != null && note.reminderTimestamp > Date.now() && !note.isTrashed;
  const showStatusCluster = !isSelected && (note.isPinned || hasReminder);
  const checkedCount = note.checklist.filter((item) => item.isChecked).length;
  const showChecklist = note.checklist.length > 0;
  const showLabels = note.labels.length > 0;
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
      </div>
    );
  };

  const labelChips = showLabels ? (
    <div className={`flex flex-wrap gap-1 ${isList ? 'mt-1.5' : 'mt-1.5'}`}>
      {note.labels.slice(0, labelLimit).map((label) =>
        onLabelClick ? (
          <button
            key={label.id}
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              onLabelClick(label.name);
            }}
            className={`rounded-full font-semibold uppercase tracking-wider hover:opacity-80 pointer-events-auto focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary ${
              isDense || isList ? 'px-1.5 py-px text-[9px]' : 'px-2 py-0.5 text-[10px]'
            }`}
            style={labelChipStyle}
          >
            {label.name}
          </button>
        ) : (
          <span
            key={label.id}
            className={`rounded-full font-semibold uppercase tracking-wider ${
              isDense || isList ? 'px-1.5 py-px text-[9px]' : 'px-2 py-0.5 text-[10px]'
            }`}
            style={labelChipStyle}
          >
            {label.name}
          </span>
        ),
      )}
      {note.labels.length > labelLimit ? (
        <span className="self-center text-[10px] font-semibold uppercase tracking-wider opacity-70">
          +{note.labels.length - labelLimit}
        </span>
      ) : null}
    </div>
  ) : null;

  const openLabel = [title, ...statusParts].join(', ');

  return (
    <article
      className={`relative flex h-auto w-full overflow-hidden rounded-note text-left transition-[transform,box-shadow,background-color] duration-200 ${
        isList
          ? 'flex-row items-stretch gap-0'
          : isDense
            ? 'flex-col p-2.5'
            : 'flex-col p-3'
      } ${showReorderHandle ? (isList ? 'pl-9' : 'pl-11') : ''} ${
        isSelected
          ? 'ring-2 ring-brand-primary ring-offset-2 ring-offset-true-surface'
          : note.color === 0
            ? 'border border-brand-outline/40 hover:border-brand-outline/70'
            : ''
      }`}
      style={surface}
    >
      {/* Stretch control — keeps nested buttons valid (no role=button wrapping buttons). */}
      <button
        type="button"
        className="absolute inset-0 z-0 cursor-pointer rounded-note focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary"
        onClick={handleClick}
        aria-pressed={isSelected || undefined}
        aria-label={openLabel}
        {...(onLongPress ? longPressProps : {})}
      />

      {showReorderHandle && reorderHandleProps ? (
        <button
          type="button"
          aria-label="Reorder note"
          className={`absolute left-0 top-1/2 z-10 flex -translate-y-1/2 cursor-grab touch-none items-center justify-center text-brand-muted/40 pointer-events-auto active:cursor-grabbing focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary ${
            isList ? 'size-8' : 'size-10'
          }`}
          {...reorderHandleProps}
        >
          <DragHandleIcon size={isList ? 16 : 18} />
        </button>
      ) : null}

      <div className={`relative z-[1] flex min-h-0 min-w-0 flex-1 pointer-events-none ${
        isList ? 'flex-row items-stretch' : 'flex-col'
      }`}>
      {isList ? (
        <>
          <span
            className={`my-2 ml-2.5 w-0.5 shrink-0 rounded-full ${
              note.color !== 0 ? 'bg-current/30' : 'bg-brand-outline/70'
            }`}
            aria-hidden
          />

          <div className="flex min-w-0 flex-1 items-start gap-3 px-3 py-2.5 sm:gap-3.5 sm:px-3.5 sm:py-2.5">
            <div className="min-w-0 flex-1">
              <h2 className="line-clamp-1 text-[14px] font-bold leading-snug tracking-[-0.015em] sm:text-[15px]">
                {highlight(title)}
              </h2>
              {showBody ? (
                <p className="mt-1.5 line-clamp-1 text-[12px] leading-snug opacity-70 sm:mt-2 sm:line-clamp-2 sm:text-[13px]">
                  {highlight(previewBody)}
                </p>
              ) : null}
              {showChecklist ? (
                <p className="mt-1 text-[10px] font-medium tracking-wide opacity-60">
                  {checkedCount}/{note.checklist.length} checked
                </p>
              ) : null}
              {labelChips}
            </div>

            <div className="flex shrink-0 flex-col items-end gap-1 pt-0.5">
              {statusIcons(14)}
              <time
                dateTime={new Date(note.timestamp).toISOString()}
                className="text-[10px] font-medium tabular-nums tracking-wide opacity-55 sm:text-[11px]"
              >
                {timeLabel}
              </time>
            </div>
          </div>
        </>
      ) : (
        <>
          <div className="flex items-start gap-1.5">
            <h2
              className={`min-w-0 flex-1 font-bold ${
                isDense
                  ? 'line-clamp-2 text-[13px] leading-snug tracking-[-0.01em]'
                  : 'line-clamp-2 text-[14px] leading-snug tracking-[-0.02em]'
              }`}
            >
              {highlight(title)}
            </h2>
            <div className="flex shrink-0 flex-col items-end gap-0.5">
              {statusIcons(isDense ? 12 : 13)}
              <time
                dateTime={new Date(note.timestamp).toISOString()}
                className={`font-medium tabular-nums tracking-wide opacity-50 ${
                  isDense ? 'text-[9px]' : 'text-[10px]'
                }`}
              >
                {timeLabel}
              </time>
            </div>
          </div>

          {showBody ? (
            <p
              className={
                isDense
                  ? 'mt-1.5 line-clamp-4 text-[11px] leading-snug opacity-70'
                  : 'mt-1.5 line-clamp-5 text-[12px] leading-snug opacity-70'
              }
            >
              {highlight(previewBody)}
            </p>
          ) : null}

          {showChecklist ? (
            isDense ? (
              <p className="mt-1 text-[10px] font-medium tracking-wide opacity-60">
                {checkedCount}/{note.checklist.length} checked
              </p>
            ) : (
              <div className="mt-1.5 space-y-1">
                {note.checklist.slice(0, 3).map((item) => (
                  <div key={item.id} className="flex items-center gap-1.5">
                    {item.isChecked ? (
                      <CheckCircleIcon size={13} className="shrink-0 opacity-70" />
                    ) : (
                      <CheckCircleOutlineIcon size={13} className="shrink-0 opacity-70" />
                    )}
                    <span className="line-clamp-1 text-[12px] leading-snug opacity-70">
                      {highlight(stripMarkdownForPreview(item.text))}
                    </span>
                  </div>
                ))}
              </div>
            )
          ) : null}

          {labelChips}
        </>
      )}
      </div>
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
