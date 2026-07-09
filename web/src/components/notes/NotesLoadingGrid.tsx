interface NotesLoadingGridProps {
  columns: 1 | 2 | 3;
}

export function NotesLoadingGrid({ columns }: NotesLoadingGridProps) {
  const count = columns === 1 ? 4 : columns === 2 ? 6 : 9;

  return (
    <div
      className={`gap-note-gap px-3 pb-24 pt-2 sm:px-4 lg:px-6 ${
        columns === 1 ? 'flex flex-col' : columns === 2 ? 'columns-2' : 'columns-3'
      }`}
      style={columns > 1 ? { columnGap: '12px' } : undefined}
      aria-hidden
    >
      {Array.from({ length: count }, (_, index) => (
        <div
          key={index}
          className={`animate-pulse rounded-note bg-true-surface-variant/60 ${
            columns > 1 ? 'mb-note-gap break-inside-avoid' : ''
          }`}
          style={{ height: columns === 1 ? 120 : 140 }}
        />
      ))}
    </div>
  );
}
