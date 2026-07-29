import type { ViewColumns } from '@/store/uiStore';

interface NotesLoadingGridProps {
  viewPreference: ViewColumns;
}

const CARD_MIN_PX: Record<2 | 3, number> = {
  2: 260,
  3: 152,
};

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
  const columnGapPx = viewPreference === 3 ? 8 : 10;

  return (
    <div
      className={`w-full px-3 pb-24 pt-2 sm:px-4 lg:px-6 ${
        isList
          ? `mx-auto grid max-w-content grid-cols-1 ${gapClass}`
          : 'mx-auto max-w-content'
      }`}
      style={
        isList
          ? undefined
          : {
              columnWidth: cardMin,
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
