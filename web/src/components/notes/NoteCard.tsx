import {
  ArchiveIcon,
  CheckCircleIcon,
  CheckCircleOutlineIcon,
  DragHandleIcon,
  ImageIcon,
  NotesIcon,
  NotificationIcon,
  PinIcon,
  PinOffIcon,
  TrashIcon,
} from '@/components/icons/Icons';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import { useLongPress } from '@/hooks/useLongPress';
import { formatListTimestamp } from '@/lib/text/dateTime';
import { highlightSearchText } from '@/lib/text/highlightSearch';
import { stripMarkdownForPreview } from '@/lib/text/markdown';
import type { Note } from '@/types/note';
import { noteSurfaceStyle } from '@/theme/contrast';
import { useNotePaletteDark } from '@/theme/useNotePaletteDark';
import { argbToCssAlpha } from '@/theme/colors';
import { memo, type PointerEventHandler, type ReactNode } from 'react';

export interface NoteReorderHandleProps {
  onPointerDown: PointerEventHandler<HTMLButtonElement>;
  onPointerMove: PointerEventHandler<HTMLButtonElement>;
  onPointerUp: PointerEventHandler<HTMLButtonElement>;
  onPointerCancel: PointerEventHandler<HTMLButtonElement>;
}

export type NoteCardDensity = 'list' | 'grid' | 'dense';

export interface NoteCardProps {
  note: Note;
  onClick: () => void;
  /** @deprecated use density */
  compact?: boolean;
  density?: NoteCardDensity;
  onLabelClick?: (labelName: string) => void;
  searchQuery?: string;
  isSelected?: boolean;
  onLongPress?: () => void;
  onToggleSelect?: () => void;
  onPinToggle?: () => void;
  onArchive?: () => void;
  onRestore?: () => void;
  onTrash?: () => void;
  isPermanentDelete?: boolean;
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
  onToggleSelect,
  onPinToggle,
  onArchive,
  onRestore,
  onTrash,
  isPermanentDelete = false,
  showReorderHandle = false,
  reorderHandleProps,
}: NoteCardProps) {
  const density: NoteCardDensity = densityProp ?? (compact ? 'grid' : 'list');
  const isList = density === 'list';
  const isDense = density === 'dense';
  const isDarkPalette = useNotePaletteDark();

  const surface = noteSurfaceStyle(note.color, { isDarkPalette });
  const contentColor = note.color === 0 ? 'rgb(var(--primary-rgb))' : surface.color;
  const labelChipStyle =
    note.color === 0
      ? { backgroundColor: 'rgba(255,255,255,0.12)', color: contentColor }
      : { backgroundColor: argbToCssAlpha(note.color, 0.1), color: contentColor };
  const showBody = note.content.length > 0;
  const previewBody = stripMarkdownForPreview(note.content);
  // D15: don't render "Untitled" in the card — an empty-title note leads with
  const title = note.title;
  const firstBodyLine = previewBody.split('\n')[0]?.trim() ?? '';
  const highlight = (text: string) => highlightSearchText(text, searchQuery);
  const hasReminder = Boolean(note.reminderTimestamp);
  const attachments = note.attachments ?? [];
  const showAttachments = attachments.length > 0;
  const showStatusCluster = !isSelected && (note.isPinned || hasReminder || showAttachments);
  const checklist = note.checklist ?? [];
  const labels = note.labels ?? [];
  const checkedCount = checklist.filter((item) => item.isChecked).length;
  const showChecklist = checklist.length > 0;
  const showLabels = labels.length > 0;
  const labelLimit = isDense ? 1 : isList ? 3 : 2;
  const timeLabel = formatListTimestamp(note.timestamp);

  const { longPressProps, shouldSuppressClick } = useLongPress({
    onLongPress: () => onLongPress?.(),
  });

  const handleClick = (e: React.MouseEvent) => {
    if (shouldSuppressClick()) return;
    if (e.shiftKey && onToggleSelect) {
      onToggleSelect();
      return;
    }
    onClick();
  };

  const statusParts = [
    note.isPinned ? 'Pinned' : null,
    hasReminder ? 'Reminder set' : null,
    showAttachments ? 'Has image' : null,
    isSelected ? 'Selected' : null,
  ].filter(Boolean);

  const statusIcons = (size: number): ReactNode => {
    if (!showStatusCluster) return null;
    return (
      <div className="flex shrink-0 items-center gap-1.5 opacity-60" aria-hidden>
        {note.isPinned ? <PinIcon size={size} /> : null}
        {hasReminder ? <NotificationIcon size={size} /> : null}
        {showAttachments ? (
          <span
            className="flex items-center gap-0.5"
            title={`${attachments.length} attachment${attachments.length > 1 ? 's' : ''}`}
          >
            <ImageIcon size={size} />
            {attachments.length > 1 ? (
              <span className="text-[10px] font-bold">{attachments.length}</span>
            ) : null}
          </span>
        ) : null}
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
            className={`inline-flex min-h-[24px] items-center rounded-full font-semibold uppercase tracking-wider hover:opacity-80 pointer-events-auto focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary ${
              isDense || isList ? 'px-2 py-0.5 text-[9px]' : 'px-2.5 py-0.5 text-[10px]'
            }`}
            style={labelChipStyle}
          >
            {label.name}
          </button>
        ) : (
          <span
            key={label.id}
            className={`inline-flex min-h-[20px] items-center rounded-full font-semibold uppercase tracking-wider ${
              isDense || isList ? 'px-1.5 py-px text-[9px]' : 'px-2 py-0.5 text-[10px]'
            }`}
            style={labelChipStyle}
          >
            {label.name}
          </span>
        ),
      )}
      {note.labels.length > labelLimit ? (
        <span className="self-center text-[10px] font-semibold uppercase tracking-wider opacity-80">
          +{note.labels.length - labelLimit}
        </span>
      ) : null}
    </div>
  ) : null;

  const openLabel = [title || firstBodyLine || 'Untitled', ...statusParts].join(', ');

  return (
    <article
      className={`group relative flex h-auto w-full overflow-hidden rounded-note text-left transition-all duration-200 hover:shadow-lg ${
        isList
          ? 'flex-row items-stretch gap-0'
          : isDense
            ? 'flex-col p-4'
            : 'flex-col p-5'
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

      {/* Selection checkbox button (UX-B) */}
      {onToggleSelect ? (
        <button
          type="button"
          role="checkbox"
          aria-checked={isSelected}
          aria-label={isSelected ? 'Deselect note' : 'Select note'}
          onClick={(event) => {
            event.stopPropagation();
            onToggleSelect();
          }}
          className={`absolute left-2.5 top-2.5 z-10 flex size-6 items-center justify-center rounded-full pointer-events-auto transition-all ${CHROME_FOCUS} ${
            isSelected
              ? 'bg-brand-primary text-true-surface opacity-100 shadow-sm'
              : 'opacity-0 group-hover:opacity-100 focus:opacity-100 group-focus-within:opacity-100 bg-true-surface/85 text-brand-muted hover:text-brand-primary hover:bg-true-surface shadow-sm border border-brand-outline/40'
          }`}
        >
          {isSelected ? (
            <span className="text-[12px] font-bold">✓</span>
          ) : (
            <span className="size-2 rounded-full border border-current opacity-60" />
          )}
        </button>
      ) : null}

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
        <div className="flex min-w-0 flex-1 items-start gap-3 px-3.5 py-3.5 sm:gap-4 sm:px-4 sm:py-4">
          <div className="min-w-0 flex-1">
            {title ? (
              <h2 className="line-clamp-2 break-words text-note-title tracking-[-0.02em] sm:line-clamp-2">
                {highlight(title)}
              </h2>
            ) : null}
            {showBody ? (
              <p className={`break-words text-note-body opacity-80 sm:line-clamp-3 ${title ? 'mt-2 line-clamp-2 sm:mt-2.5' : 'line-clamp-3 font-semibold'}`}>
                {highlight(previewBody)}
              </p>
            ) : null}
            {showChecklist ? (
              <p className="mt-1.5 text-[11px] font-medium tracking-wide opacity-80">
                {checkedCount}/{note.checklist.length} checked
              </p>
            ) : null}
            {labelChips}
          </div>

          <div className="flex shrink-0 flex-col items-end gap-1.5 pt-0.5">
            {statusIcons(15)}
            <time
              dateTime={new Date(note.timestamp).toISOString()}
              className="text-[11px] font-medium tabular-nums tracking-wide opacity-80 sm:text-[12px]"
            >
              {timeLabel}
            </time>
          </div>
        </div>
      ) : (
        <>
          <div className="flex items-start gap-2">
            {title ? (
              <h2
                className={`min-w-0 flex-1 break-words font-semibold tracking-[-0.02em] ${
                  isDense
                    ? 'line-clamp-2 text-[15px] leading-snug'
                    : 'line-clamp-3 text-note-title'
                }`}
              >
                {highlight(title)}
              </h2>
            ) : (
              // Without a title the status cluster still needs a flex sibling to push it right
              <span className="min-w-0 flex-1" aria-hidden />
            )}
            <div className="flex shrink-0 flex-col items-end gap-1">
              {statusIcons(isDense ? 13 : 14)}
              <time
                dateTime={new Date(note.timestamp).toISOString()}
                className={`font-medium tabular-nums tracking-wide opacity-80 ${
                  isDense ? 'text-[10px]' : 'text-[11px]'
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
                  ? 'mt-2 line-clamp-5 break-words text-[13px] leading-relaxed opacity-80'
                  : 'mt-3 line-clamp-7 break-words text-note-body leading-relaxed opacity-80'
              }
            >
              {highlight(previewBody)}
            </p>
          ) : null}

          {showChecklist ? (
            isDense ? (
              <p className="mt-1.5 text-[11px] font-medium tracking-wide opacity-80">
                {checkedCount}/{note.checklist.length} checked
              </p>
            ) : (
              <div className="mt-2 space-y-1.5">
                {note.checklist.slice(0, 3).map((item) => (
                  <div key={item.id} className="flex items-center gap-2">
                    {item.isChecked ? (
                      <CheckCircleIcon size={14} className="shrink-0 opacity-60" />
                    ) : (
                      <CheckCircleOutlineIcon size={14} className="shrink-0 opacity-60" />
                    )}
                    <span
                      className={`line-clamp-1 break-words text-note-body ${
                        item.isChecked ? 'line-through opacity-50' : 'opacity-80'
                      }`}
                    >
                      {highlight(stripMarkdownForPreview(item.text))}
                    </span>
                  </div>
                ))}
                {note.checklist.length > 3 ? (
                  <p className="text-[11px] font-medium tracking-wide opacity-60">
                    +{note.checklist.length - 3} more
                  </p>
                ) : null}
              </div>
            )
          ) : null}

          {labelChips}
        </>
      )}
      </div>

      {/* Desktop hover/focus quick actions (UX-05) */}
      {!isSelected && (onPinToggle || onArchive || onRestore || onTrash) ? (
        <div
          className="absolute right-2 bottom-2 z-10 hidden sm:flex items-center gap-0.5 rounded-full bg-true-surface/90 backdrop-blur-sm border border-brand-outline/30 p-0.5 shadow-sm opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 transition-opacity pointer-events-auto"
          role="toolbar"
          aria-label="Note quick actions"
        >
          {onPinToggle ? (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onPinToggle();
              }}
              aria-label={note.isPinned ? 'Unpin note' : 'Pin note'}
              title={note.isPinned ? 'Unpin note' : 'Pin note'}
              className={`flex size-6 items-center justify-center rounded-full text-brand-muted hover:text-brand-primary hover:bg-brand-primary/10 transition-colors ${CHROME_FOCUS}`}
            >
              {note.isPinned ? <PinOffIcon size={14} /> : <PinIcon size={14} />}
            </button>
          ) : null}
          {onArchive ? (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onArchive();
              }}
              aria-label="Archive note"
              title="Archive note"
              className={`flex size-6 items-center justify-center rounded-full text-brand-muted hover:text-brand-primary hover:bg-brand-primary/10 transition-colors ${CHROME_FOCUS}`}
            >
              <ArchiveIcon size={14} />
            </button>
          ) : null}
          {onRestore ? (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onRestore();
              }}
              aria-label="Restore note"
              title="Restore note"
              className={`flex size-6 items-center justify-center rounded-full text-brand-muted hover:text-brand-primary hover:bg-brand-primary/10 transition-colors ${CHROME_FOCUS}`}
            >
              <NotesIcon size={14} />
            </button>
          ) : null}
          {onTrash ? (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onTrash();
              }}
              aria-label={isPermanentDelete ? 'Delete permanently' : 'Delete note'}
              title={isPermanentDelete ? 'Delete permanently' : 'Delete note'}
              className={`flex size-6 items-center justify-center rounded-full text-brand-muted hover:text-red-500 hover:bg-red-500/10 transition-colors ${CHROME_FOCUS}`}
            >
              <TrashIcon size={14} />
            </button>
          ) : null}
        </div>
      ) : null}
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
    prev.onLabelClick === next.onLabelClick &&
    prev.onToggleSelect === next.onToggleSelect &&
    prev.onPinToggle === next.onPinToggle &&
    prev.onArchive === next.onArchive &&
    prev.onRestore === next.onRestore &&
    prev.onTrash === next.onTrash &&
    prev.isPermanentDelete === next.isPermanentDelete
  );
}

export const NoteCard = memo(NoteCardImpl, noteCardPropsAreEqual);
