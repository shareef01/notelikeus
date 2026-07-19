import type { ReactNode } from 'react';
import { ArchiveIcon, CloseIcon, NotesIcon, PinIcon, TrashIcon } from '@/components/icons/Icons';
import type { NoteFilter } from '@/types/note';

const CHROME_FOCUS =
  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary';

interface SelectionBarProps {
  selectedCount: number;
  allFilteredSelected: boolean;
  currentFilter: NoteFilter;
  selectionAllPinned?: boolean;
  onClearSelection: () => void;
  onToggleSelectAll: () => void;
  selectionAllPinned?: boolean;
  onPinToggle?: () => void;
  onArchive?: () => void;
  onRestore?: () => void;
  onTrash?: () => void;
  onPermanentDelete?: () => void;
}

export function SelectionBar({
  selectedCount,
  allFilteredSelected,
  currentFilter,
  selectionAllPinned = false,
  onClearSelection,
  onToggleSelectAll,
  selectionAllPinned = false,
  onPinToggle,
  onArchive,
  onRestore,
  onTrash,
  onPermanentDelete,
}: SelectionBarProps) {
  const label = selectedCount === 1 ? '1 selected' : `${selectedCount} selected`;

  return (
    <div className="flex h-14 items-center gap-2 sm:h-16 sm:gap-3">
      <button
        type="button"
        onClick={onClearSelection}
        className={`flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted hover:bg-white/5 ${CHROME_FOCUS}`}
        aria-label="Clear selection"
      >
        <CloseIcon size={22} />
      </button>

      <div className="min-w-0 flex-1">
        <p className="truncate text-base font-semibold text-brand-primary">{label}</p>
        <button
          type="button"
          onClick={onToggleSelectAll}
          className="text-xs font-medium text-brand-muted hover:text-brand-primary"
        >
          {allFilteredSelected ? 'Deselect all' : 'Select all'}
        </button>
      </div>

      <div className="flex shrink-0 items-center gap-1">
        {currentFilter === 'active' && onPinToggle ? (
          <IconAction label={selectionAllPinned ? 'Unpin' : 'Pin'} onClick={onPinToggle}>
            <PinIcon size={20} className={selectionAllPinned ? 'opacity-100' : 'opacity-55'} />
          </IconAction>
        ) : null}

        {currentFilter === 'active' && onArchive ? (
          <IconAction label="Archive" onClick={onArchive}>
            <ArchiveIcon size={20} />
          </IconAction>
        ) : null}

        {(currentFilter === 'archived' || currentFilter === 'trashed') && onRestore ? (
          <IconAction label="Restore" onClick={onRestore}>
            <NotesIcon size={20} />
          </IconAction>
        ) : null}

        {currentFilter !== 'trashed' && onTrash ? (
          <IconAction label="Move to trash" onClick={onTrash}>
            <TrashIcon size={20} />
          </IconAction>
        ) : null}

        {currentFilter === 'trashed' && onPermanentDelete ? (
          <IconAction label="Delete permanently" onClick={onPermanentDelete} danger>
            <TrashIcon size={20} />
          </IconAction>
        ) : null}
      </div>
    </div>
  );
}

function IconAction({
  label,
  onClick,
  children,
  danger = false,
}: {
  label: string;
  onClick: () => void;
  children: ReactNode;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className={`flex size-10 items-center justify-center rounded-full transition-colors ${CHROME_FOCUS} ${
        danger
          ? 'text-red-400 hover:bg-red-500/10'
          : 'text-brand-muted interactive-hover hover:text-brand-primary'
      }`}
    >
      {children}
    </button>
  );
}
