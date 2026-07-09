import { DragHandleIcon } from '@/components/icons/Icons';
import { NoteCard } from '@/components/notes/NoteCard';
import { NoteSectionHeader } from '@/components/notes/NoteSectionHeader';
import type { Note } from '@/types/note';
import { useMemo } from 'react';

interface NoteStaggeredGridProps {
  notes: Note[];
  columns: 1 | 2 | 3;
  onNoteClick: (note: Note) => void;
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

export function NoteStaggeredGrid({ notes, columns, onNoteClick }: NoteStaggeredGridProps) {
  const { pinned, others, showSections } = useMemo(() => groupNotes(notes), [notes]);
  const compact = columns > 1;

  if (columns === 1) {
    return (
      <div className="flex flex-col gap-note-gap px-3 pb-24 pt-2 sm:px-4 lg:px-6">
        {showSections ? <NoteSectionHeader title="Pinned" /> : null}
        {pinned.map((note) => (
          <ListRow key={note.id} note={note} onClick={() => onNoteClick(note)} compact={compact} />
        ))}
        {showSections ? <NoteSectionHeader title="Others" /> : null}
        {others.map((note) => (
          <ListRow key={note.id} note={note} onClick={() => onNoteClick(note)} compact={compact} />
        ))}
      </div>
    );
  }

  const columnClass = COLUMN_CLASSES[columns];

  const renderMasonrySection = (sectionNotes: Note[]) =>
    sectionNotes.map((note) => (
      <div key={note.id} className="mb-note-gap break-inside-avoid">
        <NoteCard note={note} onClick={() => onNoteClick(note)} compact={compact} />
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

function ListRow({
  note,
  onClick,
  compact,
}: {
  note: Note;
  onClick: () => void;
  compact: boolean;
}) {
  return (
    <div className="flex items-center gap-0">
      <div className="hidden w-8 shrink-0 justify-center pl-2 md:flex lg:w-10 lg:pl-4" aria-hidden>
        <DragHandleIcon size={24} className="text-brand-muted/40" />
      </div>
      <div className="min-w-0 flex-1">
        <NoteCard note={note} onClick={onClick} compact={compact} />
      </div>
    </div>
  );
}
