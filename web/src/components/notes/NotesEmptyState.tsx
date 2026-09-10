import { BrandMark } from '@/components/brand/BrandMark';
import { ArchiveIcon, TrashIcon } from '@/components/icons/Icons';
import type { ReactNode } from 'react';

interface NotesEmptyStateProps {
  message: string;
  subtitle?: string | null;
  icon?: 'brand' | 'archive' | 'trash';
  action?: ReactNode;
  recentSearches?: string[];
  onRecentSearchClick?: (query: string) => void;
}

/**
 * Empty State Overhaul (Web)
 * Synchronized with Android Elite Standards: 20% opacity large icons, centered medium text.
 * Added: Recent search suggestions for empty search results.
 */
export function NotesEmptyState({
  message,
  subtitle,
  icon = 'brand',
  action,
  recentSearches = [],
  onRecentSearchClick
}: NotesEmptyStateProps) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-6 py-16 text-center sm:px-10 sm:py-20 lg:px-16">
      <div className="mb-6 opacity-25 sm:mb-8" aria-hidden>
        {icon === 'brand' ? (
          <BrandMark size={72} />
        ) : icon === 'archive' ? (
          <ArchiveIcon size={72} className="text-brand-primary" />
        ) : (
          <TrashIcon size={72} className="text-brand-primary" />
        )}
      </div>
      <p className="text-note-title text-brand-primary/90">{message}</p>
      {subtitle ? (
        <p className="mt-2 max-w-sm text-note-body text-brand-muted">
          {subtitle}
        </p>
      ) : null}

      {action ? <div className="mt-8">{action}</div> : null}

      {!action && recentSearches.length > 0 && (
        <div className="mt-10 flex flex-col items-center animate-in fade-in duration-700 sm:mt-12">
          <p className="text-section-label uppercase text-brand-muted">Recent searches</p>
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
