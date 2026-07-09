import { DragHandleIcon } from '@/components/icons/Icons';
import { NoteCard } from '@/components/notes/NoteCard';
import { NoteSectionHeader } from '@/components/notes/NoteSectionHeader';
import { SwipeableNoteCard } from '@/components/notes/SwipeableNoteCard';
import type { Note, NoteFilter } from '@/types/note';
import { useMemo } from 'react';

interface NoteListActions {
  onArchive: (note: Note) => void;
  onTrash: (note: Note) => void;
  onRestore: (note: Note) => void;
  onPermanentDelete: (note: Note) => void;
}

interface NoteStaggeredGridProps {
  notes: Note[];
  columns: 1 | 2 | 3;
  filter: NoteFilter;
  onNoteClick: (note: Note) => void;
  onNoteLongPress: (note: Note) => void;
  selectedNoteIds: string[];
  selectionMode: boolean;
  onLabelClick?: (labelName: string) => void;
  listActions?: NoteListActions;
  searchQuery?: string;
}

function groupNotes(notes: Note[]) {
  const pinned = notes.filter((note) => note.isPinned);
  const others = notes.filter((note) => !note.isPinned);
  return { pinned, others, showSections: pinned.length > 0 && others.length > 0 };
}

const COLUMN_CLASSES: Record<1 | 2 | 3, string> = {
  1: '',
  2: 'columns-2',
  3: 'columns-3',
};

export function NoteStaggeredGrid({
  notes,
  columns,
  filter,
  onNoteClick,
  onNoteLongPress,
  selectedNoteIds,
  selectionMode,
  onLabelClick,
  listActions,
  searchQuery = '',
}: NoteStaggeredGridProps) {
  const { pinned, others, showSections } = useMemo(() => groupNotes(notes), [notes]);
  const compact = columns > 1;
  const selectedSet = useMemo(() => new Set(selectedNoteIds), [selectedNoteIds]);
  const swipeEnabled = columns === 1 && Boolean(listActions) && !selectionMode;

  const cardProps = (note: Note) => ({
    note,
    compact,
    onClick: () => onNoteClick(note),
    onLongPress: () => onNoteLongPress(note),
    onLabelClick,
    searchQuery,
    isSelected: selectedSet.has(note.id),
  });

  const renderCard = (note: Note) => {
    const card = <NoteCard {...cardProps(note)} />;

    if (!swipeEnabled || !listActions) {
      return card;
    }

    if (filter === 'active') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          onArchive={() => listActions.onArchive(note)}
          onTrash={() => listActions.onTrash(note)}
        />
      );
    }

    if (filter === 'archived') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          onRestore={() => listActions.onRestore(note)}
          onTrash={() => listActions.onTrash(note)}
        />
      );
    }

    if (filter === 'trashed') {
      return (
        <SwipeableNoteCard
          {...cardProps(note)}
          onRestore={() => listActions.onRestore(note)}
          onTrash={() => listActions.onPermanentDelete(note)}
        />
      );
    }

    return card;
  };

  if (columns === 1) {
    return (
      <div className="flex flex-col gap-note-gap px-3 pb-24 pt-2 sm:px-4 lg:px-6">
        {showSections ? <NoteSectionHeader title="Pinned" /> : null}
        {pinned.map((note) => (
          <ListRow key={note.id} note={note} render={() => renderCard(note)} />
        ))}
        {showSections ? <NoteSectionHeader title="Others" /> : null}
        {others.map((note) => (
          <ListRow key={note.id} note={note} render={() => renderCard(note)} />
        ))}
      </div>
    );
  }

  const columnClass = COLUMN_CLASSES[columns];
  const renderMasonrySection = (sectionNotes: Note[]) =>
    sectionNotes.map((note) => (
      <div key={note.id} className="mb-note-gap break-inside-avoid">
        {renderCard(note)}
      </div>
    ));

  return (
    <div
      className={`${columnClass} gap-note-gap px-3 pb-24 pt-2 sm:px-4 lg:px-6`}
      style={{ columnGap: '12px' }}
    >
      {showSections ? (
        <div className="mb-note-gap break-inside-avoid">
          <NoteSectionHeader title="Pinned" />
        </div>
      ) : null}
      {renderMasonrySection(pinned)}
      {showSections ? (
        <div className="mb-note-gap break-inside-avoid">
          <NoteSectionHeader title="Others" />
        </div>
      ) : null}
      {renderMasonrySection(others)}
    </div>
  );
}

function ListRow({ render }: { note: Note; render: () => React.ReactNode }) {
  return (
    <div className="flex items-center gap-0">
      <div className="hidden w-8 shrink-0 justify-center pl-2 md:flex lg:w-10 lg:pl-4" aria-hidden>
        <DragHandleIcon size={24} className="text-brand-muted/40" />
      </div>
      <div className="min-w-0 flex-1">{render()}</div>
    </div>
  );
}
