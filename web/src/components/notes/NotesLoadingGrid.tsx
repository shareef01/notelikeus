import { CARD_MIN_PX, MAX_COLUMNS } from '@/components/notes/NoteStaggeredGrid';
import type { ViewColumns } from '@/store/uiStore';

interface NotesLoadingGridProps {
  viewPreference: ViewColumns;
}

const SKELETON_HEIGHT: Record<ViewColumns, number> = {
  1: 56,
  2: 88,
  3: 72,
};

export function NotesLoadingGrid({ viewPreference }: NotesLoadingGridProps) {
  const isList = viewPreference === 1;
  const count = isList ? 5 : viewPreference === 2 ? 8 : 12;
  const gapClass =
    viewPreference === 3
      ? 'gap-1.5 sm:gap-2'
      : isList
        ? 'gap-1.5'
        : 'gap-2 sm:gap-2.5';
  const cardMin = isList ? 0 : CARD_MIN_PX[viewPreference as 2 | 3];
  const columnGapPx = viewPreference === 3 ? 12 : 16;

  return (
    <div
      className={`w-full px-shell pb-24 pt-2 ${
        isList
          ? `mx-auto grid max-w-content grid-cols-1 ${gapClass}`
          : 'mx-auto max-w-content'
      }`}
      style={
        isList
          ? undefined
          : {
              columnWidth: cardMin,
              columnCount: MAX_COLUMNS[viewPreference as 2 | 3],
              columnGap: columnGapPx,
            }
      }
      aria-hidden
    >
      {Array.from({ length: count }, (_, index) => (
        <div
          key={index}
          className={`animate-pulse rounded-note bg-true-surface-variant/60 ${
            isList ? '' : 'mb-2 break-inside-avoid'
          }`}
          style={{ height: SKELETON_HEIGHT[viewPreference] }}
        />
      ))}
    </div>
  );
}
