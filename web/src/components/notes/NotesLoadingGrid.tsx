interface NotesLoadingGridProps {
  columns: number;
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
          className={`rounded-note ${columns > 1 ? 'mb-note-gap break-inside-avoid' : 'mb-3'}`}
        >
          <div className="animate-pulse rounded-note bg-white/[0.04]">
            <div className="space-y-3 p-5">
              <div className="h-4 w-3/4 rounded-md bg-white/[0.06]" />
              <div className="h-3 w-full rounded-md bg-white/[0.04]" />
              <div className="h-3 w-5/6 rounded-md bg-white/[0.04]" />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
