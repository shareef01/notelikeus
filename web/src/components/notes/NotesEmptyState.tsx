import { BrandMark } from '@/components/brand/BrandMark';
import { ArchiveIcon, NotesIcon, TrashIcon } from '@/components/icons/Icons';
import type { ReactNode } from 'react';

interface NotesEmptyStateProps {
  message?: string;
  subtitle?: string | null | undefined;
  icon?: 'brand' | 'archive' | 'trash';
  variant?: 'list' | 'selection';
  action?: ReactNode;
  recentSearches?: string[];
  onRecentSearchClick?: (query: string) => void;
}

/**
 * Empty State Overhaul (Web)
 * Masterpiece standards: 20% opacity icons, centered bold text, high-density layout.
 */
export function NotesEmptyState({
  message,
  subtitle,
  icon = 'brand',
  variant = 'list',
  action,
  recentSearches = [],
  onRecentSearchClick
}: NotesEmptyStateProps) {

  if (variant === 'selection') {
    return (
      <div className="flex flex-col items-center gap-6 animate-in fade-in zoom-in-95 duration-700 ease-out">
        <div className="opacity-15 grayscale brightness-200">
          <NotesIcon size={96} />
        </div>
        <div className="space-y-2">
          <h3 className="text-xl font-bold tracking-tight text-brand-primary/90">Select a note</h3>
          <p className="max-w-[280px] text-[15px] leading-relaxed text-brand-muted/70">
            Choose a note to view or edit, or create a new one.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col items-center justify-center px-6 py-16 text-center sm:px-10 lg:px-16 animate-in fade-in duration-500">
      <div className="mb-7 opacity-[0.15]">
        {icon === 'brand' ? (
          <BrandMark size={64} />
        ) : icon === 'archive' ? (
          <ArchiveIcon size={64} className="text-brand-primary" />
        ) : (
          <TrashIcon size={64} className="text-brand-primary" />
        )}
      </div>
      <p className="text-[17px] font-bold tracking-tight text-brand-primary/70">{message}</p>
      {subtitle ? (
        <p className="mt-2 max-w-xs text-[13px] font-medium leading-[1.5em] text-brand-muted/50">
          {subtitle}
        </p>
      ) : null}

      {action ? <div className="mt-7">{action}</div> : null}

      {!action && recentSearches.length > 0 && (
        <div className="mt-12 flex flex-col items-center animate-in fade-in duration-700">
          <p className="text-[12px] font-bold uppercase tracking-[1px] text-brand-muted/65">
            Recent searches
          </p>
          <div className="mt-4 flex flex-wrap justify-center gap-2">
            {recentSearches.map((query) => (
              <button
                key={query}
                type="button"
                onClick={() => onRecentSearchClick?.(query)}
                className="filter-chip filter-chip-inactive cursor-pointer"
              >
                {query}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
