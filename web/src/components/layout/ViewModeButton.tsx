import { useEffect, useRef, useState } from 'react';
import type { ViewColumns } from '@/store/uiStore';
import { GridViewIcon } from '@/components/icons/Icons';

const VIEW_ORDER: ViewColumns[] = [1, 2, 3];

const VIEW_LABELS: Record<ViewColumns, string> = {
  1: 'List',
  2: 'Grid (2 columns)',
  3: 'Grid (3 columns)',
};

interface ViewModeButtonProps {
  viewColumns: ViewColumns;
  onViewColumnsChange: (columns: ViewColumns) => void;
}

export function ViewModeButton({ viewColumns, onViewColumnsChange }: ViewModeButtonProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  return (
    <div ref={rootRef} className="relative shrink-0">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className="flex size-10 items-center justify-center rounded-full text-brand-muted interactive-hover"
        aria-label={`View mode: ${VIEW_LABELS[viewColumns]}`}
        aria-expanded={open}
        aria-haspopup="listbox"
      >
        <GridViewIcon size={20} />
      </button>
      {open ? (
        <div
          role="listbox"
          aria-label="Choose view mode"
          className="absolute right-0 top-full z-40 mt-1 min-w-[11rem] overflow-hidden rounded-note border border-brand-outline/50 bg-true-surface py-1 shadow-lg"
        >
          {VIEW_ORDER.map((columns) => (
            <button
              key={columns}
              type="button"
              role="option"
              aria-selected={columns === viewColumns}
              onClick={() => {
                onViewColumnsChange(columns);
                setOpen(false);
              }}
              className={`flex w-full px-3 py-2.5 text-left text-sm transition-colors ${
                columns === viewColumns
                  ? 'bg-true-surface-variant/70 font-semibold text-brand-primary'
                  : 'text-brand-primary interactive-hover'
              }`}
            >
              {VIEW_LABELS[columns]}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
