import { BrandMark } from '@/components/brand/BrandMark';

import { ViewModeButton } from '@/components/layout/ViewModeButton';
import { FilterRow } from '@/components/layout/FilterRow';

import { SelectionBar } from '@/components/layout/SelectionBar';

import { AddIcon, CloseIcon, MenuIcon, ScheduleIcon, SettingsIcon } from '@/components/icons/Icons';

import type { Label } from '@/types/label';

import type { NoteFilter } from '@/types/note';

import type { ViewColumns } from '@/store/uiStore';

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

  onViewColumnsChange: (columns: ViewColumns) => void;

  onNewNote?: () => void;

  viewColumns: ViewColumns;

  showNewNote?: boolean;

  selectionMode?: boolean;

  selectionAllPinned?: boolean;

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

 * Search field is dedicated to query input; actions live outside the pill.

 */

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

  onViewColumnsChange,

  onNewNote,

  viewColumns,

  showNewNote = false,

  selectionMode = false,

  selectionAllPinned = false,

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

      className={`sticky top-0 z-30 border-b border-brand-outline/20 bg-true-black transition-shadow ${

        listScrolled ? 'shadow-header-scroll' : ''

      }`}

    >

      <div className="mx-auto w-full max-w-content px-3 sm:px-4 lg:px-6">

        {selectionMode && onClearSelection && onToggleSelectAll ? (

          <SelectionBar

            selectedCount={selectedCount}

            allFilteredSelected={allFilteredSelected}

            currentFilter={currentFilter}

            selectionAllPinned={selectionAllPinned}

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

        <div className="flex h-14 items-center gap-2 pt-safe sm:gap-2.5 lg:h-[3.25rem]">

          <button

            type="button"

            onClick={onMenuClick}

            className="flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted interactive-hover lg:hidden"

            aria-label="Open menu"

          >

            <MenuIcon size={22} />

          </button>



          <form

            onSubmit={handleSearchSubmit}

            className="flex h-10 min-w-0 flex-1 items-center rounded-full bg-true-surface-variant/70 px-3 sm:h-11 sm:px-4"

          >

            <input

              ref={searchInputRef}

              type="search"

              value={searchQuery}

              onChange={(event) => onSearchQueryChange(event.target.value)}

              onFocus={() => setIsSearchFocused(true)}

              onBlur={() => setTimeout(() => setIsSearchFocused(false), 200)}

              placeholder={SEARCH_PLACEHOLDERS[currentFilter]}

              className="min-w-0 flex-1 bg-transparent text-base text-brand-primary outline-none placeholder:text-brand-muted/60"

              aria-label={SEARCH_PLACEHOLDERS[currentFilter]}

            />



            {searchQuery ? (

              <button

                type="button"

                onClick={() => onSearchQueryChange('')}

                className="ml-1 flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted interactive-hover"

                aria-label="Clear search"

              >

                <CloseIcon size={20} />

              </button>

            ) : null}

          </form>



          <ViewModeButton viewColumns={viewColumns} onViewColumnsChange={onViewColumnsChange} />



          <button

            type="button"

            onClick={onProfileClick}

            className="flex size-10 shrink-0 items-center justify-center rounded-full interactive-hover lg:hidden"

            aria-label="Open settings"

          >

            <BrandMark size={32} />

          </button>

          <button

            type="button"

            onClick={onProfileClick}

            className="hidden size-10 shrink-0 items-center justify-center rounded-full text-brand-muted interactive-hover lg:flex"

            aria-label="Open settings"

          >

            <SettingsIcon size={22} />

          </button>



          {showNewNote && onNewNote ? (

            <button

              type="button"

              onClick={onNewNote}

              className="hidden shrink-0 items-center gap-1.5 rounded-note bg-brand-primary px-3.5 py-2 text-sm font-semibold text-true-black lg:flex"

            >

              <AddIcon size={18} />

              <span className="hidden xl:inline">New note</span>

              <span className="xl:hidden">New</span>

            </button>

          ) : null}

        </div>

        )}

      </div>



      <div className="mx-auto w-full max-w-content overflow-hidden">

        {showRecent ? (

          <div className="flex items-center gap-2 px-4 py-2 animate-in slide-in-from-top-2">

            <ScheduleIcon size={18} className="shrink-0 text-brand-muted/50" />

            <div className="flex flex-1 gap-2 overflow-x-auto scrollbar-hide py-1">

              {recentSearches.map((query) => (

                <button

                  key={query}

                  type="button"

                  onClick={() => onRecentSearchClick?.(query)}

                  className="filter-chip filter-chip-inactive whitespace-nowrap"

                >

                  {query}

                </button>

              ))}

            </div>

            {onClearRecentSearches ? (

              <button

                type="button"

                onClick={onClearRecentSearches}

                className="min-h-11 px-2 text-xs font-bold text-brand-primary/80 hover:text-brand-primary"

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



      {listScrolled ? <div className="h-px bg-brand-outline/35" /> : null}

    </header>

  );

}


