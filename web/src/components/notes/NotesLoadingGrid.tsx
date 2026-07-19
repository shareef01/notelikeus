import type { ViewColumns } from '@/store/uiStore';

interface NotesLoadingGridProps {
  viewPreference: ViewColumns;
}

const CARD_MIN_PX: Record<2 | 3, number> = {
  2: 220,
  3: 156,
};

const SKELETON_HEIGHT: Record<ViewColumns, number> = {
  1: 72,
  2: 140,
  3: 108,
};

export function NotesLoadingGrid({ viewPreference }: NotesLoadingGridProps) {
  const isList = viewPreference === 1;
  const count = isList ? 5 : viewPreference === 2 ? 8 : 12;
  const gapClass =
    viewPreference === 3
      ? 'gap-2 sm:gap-2.5'
      : isList
        ? 'gap-2 sm:gap-2.5'
        : 'gap-3 sm:gap-3.5';

  return (
    <div
      className={`grid w-full ${gapClass} px-3 pb-24 pt-3 sm:px-4 lg:px-6 ${
        isList ? 'max-w-3xl grid-cols-1' : 'mx-auto max-w-content items-start'
      }`}
      style={
        isList
          ? undefined
          : {
              gridTemplateColumns: `repeat(auto-fill, minmax(min(100%, ${CARD_MIN_PX[viewPreference]}px), 1fr))`,
            }
      }
      aria-hidden
    >
      {Array.from({ length: count }, (_, index) => (
        <div
          key={index}
          className="animate-pulse rounded-note bg-true-surface-variant/60"
          style={{ height: SKELETON_HEIGHT[viewPreference] }}
        />
      ))}
    </div>
  );
}
