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
    <div className="flex flex-1 flex-col items-center justify-center px-6 py-20 text-center sm:px-10 lg:px-16">
      <div className="mb-8 opacity-20">
        {icon === 'brand' ? (
          <BrandMark size={72} />
        ) : icon === 'archive' ? (
          <ArchiveIcon size={72} className="text-brand-primary" />
        ) : (
          <TrashIcon size={72} className="text-brand-primary" />
        )}
      </div>
      <p className="text-[18px] font-bold tracking-tight text-brand-primary opacity-80">{message}</p>
      {subtitle ? (
        <p className="mt-2 text-[14px] font-medium leading-[1.4em] text-brand-muted opacity-65">
          {subtitle}
        </p>
      ) : null}

      {action ? <div className="mt-8">{action}</div> : null}

      {!action && recentSearches.length > 0 && (
        <div className="mt-12 flex flex-col items-center animate-in fade-in duration-700">
          <p className="text-[12px] font-semibold uppercase tracking-[0.8px] text-brand-muted/50">
            Recent searches
          </p>
          <div className="mt-4 flex flex-wrap justify-center gap-2">
            {recentSearches.map((query) => (
              <button
                key={query}
                type="button"
                onClick={() => onRecentSearchClick?.(query)}
                className="rounded-full border border-brand-outline px-4 py-1.5 text-xs font-medium text-brand-secondary transition-all hover:border-brand-primary/30 hover:text-brand-primary active:scale-95"
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
