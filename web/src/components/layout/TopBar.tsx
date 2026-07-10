import { BrandMark } from '@/components/brand/BrandMark';
import { FilterRow } from '@/components/layout/FilterRow';
import { SelectionBar } from '@/components/layout/SelectionBar';
import { AddIcon, CloseIcon, GridViewIcon, MenuIcon } from '@/components/icons/Icons';
import type { Label } from '@/types/label';
import type { NoteFilter } from '@/types/note';
import { useState, useRef } from 'react';

const SEARCH_PLACEHOLDERS: Record<NoteFilter, string> = {
  active: 'Search notes',
  archived: 'Search archive',
  trashed: 'Search trash',
};

interface TopBarProps {
  searchQuery: string;
  onSearchQueryChange: (query: string) => void;
  currentFilter: NoteFilter;
  listScrolled: boolean;
  sortOrder: 'manual' | 'newest' | 'oldest';
  onSortOrderCycle: () => void;
  selectedColor: number | null;
  onColorSelect: (color: number | null) => void;
  labels: Label[];
  selectedLabelName: string | null;
  onLabelSelect: (name: string | null) => void;
  hasActiveFilters: boolean;
  onClearFilters: () => void;
  onMenuClick: () => void;
  onProfileClick: () => void;
  onViewModeCycle: () => void;
  onNewNote?: () => void;
  viewColumns: number;
  showNewNote?: boolean;
  selectionMode?: boolean;
  selectedCount?: number;
  allFilteredSelected?: boolean;
  onClearSelection?: () => void;
  onToggleSelectAll?: () => void;
  onBulkPin?: () => void;
  onBulkUnpin?: () => void;
  onBulkArchive?: () => void;
  onBulkRestore?: () => void;
  onBulkTrash?: () => void;
  onBulkPermanentDelete?: () => void;
  recentSearches?: string[];
  onRecentSearchClick?: (query: string) => void;
  onClearRecentSearches?: () => void;
}

/**
 * Top Bar Refinement
 * Implements "Recent Searches" logic synchronized with Android Elite standards.
 */
export function TopBar({
  searchQuery,
  onSearchQueryChange,
  currentFilter,
  listScrolled,
  sortOrder,
  onSortOrderCycle,
  selectedColor,
  onColorSelect,
  labels,
  selectedLabelName,
  onLabelSelect,
  hasActiveFilters,
  onClearFilters,
  onMenuClick,
  onProfileClick,
  onViewModeCycle,
  onNewNote,
  viewColumns,
  showNewNote = false,
  selectionMode = false,
  selectedCount = 0,
  allFilteredSelected = false,
  onClearSelection,
  onToggleSelectAll,
  onBulkPin,
  onBulkUnpin,
  onBulkArchive,
  onBulkRestore,
  onBulkTrash,
  onBulkPermanentDelete,
  recentSearches = [],
  onRecentSearchClick,
  onClearRecentSearches,
}: TopBarProps) {
  const [isSearchFocused, setIsSearchFocused] = useState(false);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const showRecent =
    !selectionMode &&
    isSearchFocused &&
    recentSearches.length > 0 &&
    !searchQuery;

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (searchQuery.trim() && onRecentSearchClick) {
      onRecentSearchClick(searchQuery.trim());
      searchInputRef.current?.blur();
    }
  };

  return (
    <header
      className={`sticky top-0 z-30 bg-true-black transition-shadow ${
        listScrolled ? 'shadow-header-scroll' : ''
      }`}
    >
      <div className="mx-auto w-full max-w-content px-3 sm:px-4 lg:px-6">
        {selectionMode && onClearSelection && onToggleSelectAll ? (
          <SelectionBar
            selectedCount={selectedCount}
            allFilteredSelected={allFilteredSelected}
            currentFilter={currentFilter}
            onClearSelection={onClearSelection}
            onToggleSelectAll={onToggleSelectAll}
            onPin={onBulkPin}
            onUnpin={onBulkUnpin}
            onArchive={onBulkArchive}
            onRestore={onBulkRestore}
            onTrash={onBulkTrash}
            onPermanentDelete={onBulkPermanentDelete}
          />
        ) : (
        <div className="flex h-14 items-center gap-2 pt-safe sm:h-16 sm:gap-3">
          <button
            type="button"
            onClick={onMenuClick}
            className="flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted hover:bg-white/5 lg:hidden"
            aria-label="Open menu"
          >
            <MenuIcon size={22} />
          </button>

          <form
            onSubmit={handleSearchSubmit}
            className="flex h-11 min-w-0 flex-1 items-center gap-1 rounded-full bg-true-surface-variant/70 px-1 sm:h-12"
          >
            <input
              ref={searchInputRef}
              type="search"
              value={searchQuery}
              onChange={(event) => onSearchQueryChange(event.target.value)}
              onFocus={() => setIsSearchFocused(true)}
              onBlur={() => setTimeout(() => setIsSearchFocused(false), 200)}
              placeholder={SEARCH_PLACEHOLDERS[currentFilter]}
              className="min-w-0 flex-1 bg-transparent px-2 text-base text-brand-primary outline-none placeholder:text-brand-muted/60 sm:px-3"
              aria-label={SEARCH_PLACEHOLDERS[currentFilter]}
            />

            {searchQuery ? (
              <button
                type="button"
                onClick={() => onSearchQueryChange('')}
                className="flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted hover:bg-white/5"
                aria-label="Clear search"
              >
                <CloseIcon size={20} />
              </button>
            ) : null}

            <button
              type="button"
              onClick={onViewModeCycle}
              className="hidden size-10 shrink-0 items-center justify-center rounded-full text-brand-muted hover:bg-white/5 sm:flex"
              aria-label={`View mode: ${viewColumns} column${viewColumns > 1 ? 's' : ''}`}
            >
              <GridViewIcon size={20} />
            </button>

            <button
              type="button"
              onClick={onProfileClick}
              className="mr-0.5 flex size-10 shrink-0 items-center justify-center"
              aria-label="Open settings"
            >
              <BrandMark size={36} />
            </button>
          </form>

          {showNewNote && onNewNote ? (
            <button
              type="button"
              onClick={onNewNote}
              className="hidden shrink-0 items-center gap-2 rounded-note bg-brand-primary px-4 py-2.5 text-sm font-semibold text-true-black lg:flex"
            >
              <AddIcon size={18} />
              New note
            </button>
          ) : null}
        </div>
        )}
      </div>

      <div className="mx-auto w-full max-w-content overflow-hidden">
        {showRecent ? (
          <div className="flex items-center gap-2 px-4 py-2 animate-in slide-in-from-top-2">
            <span className="text-brand-muted/50">🕒</span>
            <div className="flex flex-1 gap-2 overflow-x-auto scrollbar-none py-1">
              {recentSearches.map((query) => (
                <button
                  key={query}
                  type="button"
                  onClick={() => onRecentSearchClick?.(query)}
                  className="whitespace-nowrap rounded-full border border-brand-outline px-3 py-1 text-xs font-medium text-brand-secondary hover:bg-white/5"
                >
                  {query}
                </button>
              ))}
            </div>
            {onClearRecentSearches && (
              <button
                type="button"
                onClick={onClearRecentSearches}
                className="px-2 text-xs font-bold text-brand-primary/80 hover:text-brand-primary"
              >
                Clear
              </button>
            )}
          </div>
        ) : !selectionMode ? (
          <FilterRow
            sortOrder={sortOrder}
            onSortOrderCycle={onSortOrderCycle}
            selectedColor={selectedColor}
            onColorSelect={onColorSelect}
            labels={labels}
            selectedLabelName={selectedLabelName}
            onLabelSelect={onLabelSelect}
            hasActiveFilters={hasActiveFilters}
            onClearFilters={onClearFilters}
          />
        ) : null}
      </div>

      {listScrolled ? <div className="h-px bg-brand-outline/35" /> : null}
    </header>
  );
}
