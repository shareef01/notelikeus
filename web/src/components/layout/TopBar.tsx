import { FilterRow } from '@/components/layout/FilterRow';

import { SelectionBar } from '@/components/layout/SelectionBar';
import { ViewModeToggle } from '@/components/layout/ViewModeToggle';
import { AddIcon, CloseIcon, MenuIcon, SettingsIcon } from '@/components/icons/Icons';
import type { ViewColumns } from '@/store/uiStore';
import type { Label } from '@/types/label';

import type { NoteFilter } from '@/types/note';

import type { ViewColumns } from '@/store/uiStore';

import { useState, useRef } from 'react';

const CHROME_FOCUS =
  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary';

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

  onSortOrderChange: (order: 'manual' | 'newest' | 'oldest') => void;

  selectedColor: number | null;

  onColorSelect: (color: number | null) => void;

  labels: Label[];

  selectedLabelName: string | null;

  onLabelSelect: (name: string | null) => void;

  hasActiveFilters: boolean;

  onClearFilters: () => void;

  onMenuClick: () => void;

  onProfileClick: () => void;
  viewColumns: ViewColumns;
  onViewColumnsChange: (columns: ViewColumns) => void;
  onNewNote?: () => void;
  showNewNote?: boolean;

  selectionMode?: boolean;

  selectionAllPinned?: boolean;

  selectedCount?: number;

  allFilteredSelected?: boolean;

  onClearSelection?: () => void;

  onToggleSelectAll?: () => void;
  onBulkArchive?: () => void;

  onBulkRestore?: () => void;

  onBulkTrash?: () => void;

  onBulkPermanentDelete?: () => void;
  selectionAllPinned?: boolean;
  onBulkPinToggle?: () => void;
  recentSearches?: string[];

  onRecentSearchClick?: (query: string) => void;

  onClearRecentSearches?: () => void;

}

export function TopBar({

  searchQuery,

  onSearchQueryChange,

  currentFilter,

  listScrolled,

  sortOrder,

  onSortOrderChange,

  selectedColor,

  onColorSelect,

  labels,

  selectedLabelName,

  onLabelSelect,

  hasActiveFilters,

  onClearFilters,

  onMenuClick,

  onProfileClick,
  viewColumns,
  onViewColumnsChange,
  onNewNote,
  showNewNote = false,

  selectionMode = false,

  selectionAllPinned = false,

  selectedCount = 0,

  allFilteredSelected = false,

  onClearSelection,

  onToggleSelectAll,
  onBulkArchive,

  onBulkRestore,

  onBulkTrash,

  onBulkPermanentDelete,
  selectionAllPinned = false,
  onBulkPinToggle,
  recentSearches = [],

  onRecentSearchClick,

  onClearRecentSearches,

}: TopBarProps) {

  const [isSearchFocused, setIsSearchFocused] = useState(false);

  const searchInputRef = useRef<HTMLInputElement>(null);



  const showRecent =
    !selectionMode && isSearchFocused && recentSearches.length > 0 && !searchQuery;



  const handleSearchSubmit = (e: React.FormEvent) => {

    e.preventDefault();

    if (searchQuery.trim() && onRecentSearchClick) {

      onRecentSearchClick(searchQuery.trim());

      searchInputRef.current?.blur();

    }

  };



  return (
    <header
      className={`sticky top-0 z-30 bg-true-surface pt-safe transition-shadow ${
        listScrolled ? 'shadow-header-scroll' : ''
      }`}
    >
<div className="mx-auto w-full max-w-content">

        {selectionMode && onClearSelection && onToggleSelectAll ? (

          <SelectionBar

            selectedCount={selectedCount}

            allFilteredSelected={allFilteredSelected}

            currentFilter={currentFilter}

            selectionAllPinned={selectionAllPinned}

            onClearSelection={onClearSelection}

            onToggleSelectAll={onToggleSelectAll}
            onPinToggle={onBulkPinToggle}
            selectionAllPinned={selectionAllPinned}
            onArchive={onBulkArchive}

            onRestore={onBulkRestore}

            onTrash={onBulkTrash}

            onPermanentDelete={onBulkPermanentDelete}

          />

        ) : (
          <div className="flex h-14 items-center gap-2 sm:h-16 sm:gap-3">
            <button
              type="button"
              onClick={onMenuClick}
              className={`flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted hover:bg-white/5 md:hidden ${CHROME_FOCUS}`}
              aria-label="Open menu"
            >
              <MenuIcon size={22} />
            </button>

            <form
              onSubmit={handleSearchSubmit}
              className="flex h-11 min-w-0 flex-1 items-center rounded-full bg-true-surface-variant/70 px-1 sm:h-12"
            >
              <input
                ref={searchInputRef}
                type="search"
                value={searchQuery}
                onChange={(event) => onSearchQueryChange(event.target.value)}
                onFocus={() => setIsSearchFocused(true)}
                onBlur={() => setTimeout(() => setIsSearchFocused(false), 200)}
                placeholder={SEARCH_PLACEHOLDERS[currentFilter]}
                className={`min-w-0 flex-1 bg-transparent px-3 text-base text-brand-primary outline-none placeholder:text-brand-muted/60 sm:px-4 ${CHROME_FOCUS} rounded-full`}
                aria-label={SEARCH_PLACEHOLDERS[currentFilter]}
              />

              {searchQuery ? (
                <button
                  type="button"
                  onClick={() => onSearchQueryChange('')}
                  className={`mr-0.5 flex size-9 shrink-0 items-center justify-center rounded-full text-brand-muted hover:bg-white/5 ${CHROME_FOCUS}`}
                  aria-label="Clear search"
                >
                  <CloseIcon size={18} />
                </button>
              ) : null}
            </form>

            <ViewModeToggle value={viewColumns} onChange={onViewColumnsChange} />

            <button
              type="button"
              onClick={onProfileClick}
              className={`flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-white/5 hover:text-brand-primary ${CHROME_FOCUS}`}
              aria-label="Open settings"
            >
              <SettingsIcon size={22} />
            </button>

            {showNewNote && onNewNote ? (
              <button
                type="button"
                onClick={onNewNote}
                className={`hidden shrink-0 items-center gap-2 rounded-note bg-brand-primary px-4 py-2.5 text-sm font-semibold text-true-surface md:flex ${CHROME_FOCUS}`}
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
            <span className="text-xs font-medium text-brand-muted">Recent</span>
            <div className="flex flex-1 gap-2 overflow-x-auto scrollbar-none py-1">
              {recentSearches.map((query) => (

                <button

                  key={query}

                  type="button"

                  onClick={() => onRecentSearchClick?.(query)}
                  className="whitespace-nowrap rounded-full border border-brand-outline/50 px-3 py-1 text-xs font-medium text-brand-secondary hover:bg-white/5"
                >

                  {query}

                </button>

              ))}

            </div>
            {onClearRecentSearches ? (
              <button

                type="button"

                onClick={onClearRecentSearches}
                className="px-2 text-xs font-semibold text-brand-primary/80 hover:text-brand-primary"
              >

                Clear

              </button>
            ) : null}
          </div>

        ) : null}



        {!selectionMode ? (

          <FilterRow

            sortOrder={sortOrder}

            onSortOrderChange={onSortOrderChange}

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




    </header>

  );

}


