import type { NoteFilter } from '@/types/note';

export interface EmptyStateCopy {
  message: string;
  subtitle?: string;
  icon: 'brand' | 'archive' | 'trash';
  showClearFilters?: boolean;
  showCreate?: boolean;
}

/**
 * What an empty note list should say, given why it is empty.
 *
 * The order of these branches is the whole logic: "no results for your search" and "this scope is
 * empty" are different messages, and offering **Create note** in a filtered view would produce a
 * note the filter immediately hides. Search wins over filters, filters win over scope, and only
 * the genuinely-empty default offers to create anything.
 */
export function getEmptyState(
  filter: NoteFilter,
  hasActiveFilters: boolean,
  hasSearch: boolean,
): EmptyStateCopy {
  if (hasSearch) {
    return {
      message: 'No matching notes',
      subtitle: 'Try a different search term or clear filters',
      icon: 'brand',
      showClearFilters: true,
    };
  }

  if (hasActiveFilters) {
    return {
      message: 'No notes match your filters',
      subtitle: 'Try another color or label',
      icon: 'brand',
      showClearFilters: true,
    };
  }

  if (filter === 'archived') {
    return { message: 'No archived notes', icon: 'archive' };
  }

  if (filter === 'trashed') {
    return {
      message: 'No notes in trash',
      subtitle: 'Deleted notes are removed permanently',
      icon: 'trash',
    };
  }

  return {
    message: 'Notes you add appear here',
    subtitle: 'Synced automatically with your Android device',
    icon: 'brand',
    showCreate: true,
  };
}
