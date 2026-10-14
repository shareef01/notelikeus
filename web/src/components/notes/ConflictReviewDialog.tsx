import { useId, useState } from 'react';
import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogPrimaryButtonClass,
} from '@/components/ui/ModalDialog';
import {
  compareVersions,
  findConflictOriginal,
  keepBoth,
  keepCopy,
  keepOriginal,
  type ReviewLine,
} from '@/lib/notes/conflictReview';
import { useNotesStore } from '@/store/notesStore';
import { useToastStore } from '@/store/toastStore';
import { useUiStore } from '@/store/uiStore';
import type { Note } from '@/types/note';

const secondaryButtonClass =
  'rounded-note border border-brand-outline/40 px-4 py-2 text-sm font-semibold text-brand-primary transition-colors hover:bg-brand-primary/5';

function editedAt(note: Note): string {
  return new Date(note.timestamp).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function Version({ heading, note, lines }: { heading: string; note: Note; lines: ReviewLine[] }) {
  return (
    <section className="min-w-0 flex-1 rounded-note border border-brand-outline/30 p-3">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-brand-secondary">{heading}</h3>
      <p className="mt-0.5 text-xs text-brand-muted">Edited {editedAt(note)}</p>
      {note.title ? <p className="mt-2 break-words font-semibold">{note.title}</p> : null}
      <ul className="mt-1 space-y-0.5 text-sm">
        {lines.length === 0 ? <li className="text-brand-muted">(empty)</li> : null}
        {lines.map((line, index) => (
          <li
            // Lines can repeat; position is the stable identity within one rendering.
            // eslint-disable-next-line react/no-array-index-key
            key={index}
            className={`whitespace-pre-wrap break-words rounded px-1 ${
              line.changed ? 'bg-amber-500/20 font-medium' : ''
            }`}
          >
            {line.text || ' '}
            {line.changed ? <span className="sr-only"> (only in this version)</span> : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Side-by-side review of a conflicted copy and the note it was split from (UI_UX_REDESIGN §7.2).
 * Every choice is recoverable: keeping one version moves the other to Trash.
 */
export function ConflictReviewDialog() {
  const copyId = useUiStore((s) => s.conflictReviewNoteId);
  const close = useUiStore((s) => s.closeConflictReview);
  const notes = useNotesStore((s) => s.notes);
  const [busy, setBusy] = useState(false);
  const titleId = useId();

  const copy = copyId ? notes.find((note) => note.id === copyId) ?? null : null;
  const original = copy ? findConflictOriginal(copy, notes) : null;

  const run = async (action: () => Promise<void>, done: string) => {
    setBusy(true);
    try {
      await action();
      useToastStore.getState().show(done);
      close();
    } catch (error) {
      console.warn('[Notelikeus] Conflict review action failed:', error);
      useToastStore.getState().show('Could not update the notes. Nothing was changed.', 'error');
    } finally {
      setBusy(false);
    }
  };

  if (!copy) return null;
  const compared = original ? compareVersions(original, copy) : null;

  return (
    <ModalDialog open onClose={close} ariaLabelledBy={titleId} size="wide">
      <h2 id={titleId} className="text-lg font-semibold">
        This note changed on two devices
      </h2>
      <p className="mt-1 text-sm text-brand-muted">
        Nothing was lost. Pick what to keep — a version you don&apos;t keep goes to Trash, where you
        can still restore it.
      </p>

      {original && compared ? (
        <div className="mt-4 flex max-h-[55vh] flex-col gap-3 overflow-y-auto sm:flex-row">
          <Version heading="Current note" note={original} lines={compared.a} />
          <Version heading="Conflicted copy" note={copy} lines={compared.b} />
        </div>
      ) : (
        <>
          <p className="mt-4 rounded-note bg-brand-primary/5 p-3 text-sm">
            The note this copy came from couldn&apos;t be identified — it may have been renamed or
            deleted. You can keep the copy as an ordinary note or move it to Trash.
          </p>
          <div className="mt-3 max-h-[45vh] overflow-y-auto">
            <Version
              heading="Conflicted copy"
              note={copy}
              lines={compareVersions(copy, copy).a}
            />
          </div>
        </>
      )}

      <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={close} className={dialogCancelButtonClass} disabled={busy}>
          Decide later
        </button>
        {original ? (
          <>
            <button
              type="button"
              className={secondaryButtonClass}
              disabled={busy}
              onClick={() => void run(() => keepOriginal(copy), 'Kept the current note. The copy is in Trash.')}
            >
              Keep current note
            </button>
            <button
              type="button"
              className={secondaryButtonClass}
              disabled={busy}
              onClick={() =>
                void run(() => keepCopy(copy, original), 'Kept the copy. The other version is in Trash.')
              }
            >
              Keep conflicted copy
            </button>
          </>
        ) : (
          <button
            type="button"
            className={secondaryButtonClass}
            disabled={busy}
            onClick={() => void run(() => keepOriginal(copy), 'The copy is in Trash.')}
          >
            Move copy to Trash
          </button>
        )}
        <button
          type="button"
          className={dialogPrimaryButtonClass}
          disabled={busy}
          onClick={() => void run(() => keepBoth(copy), 'Kept both versions.')}
        >
          Keep both
        </button>
      </div>
    </ModalDialog>
  );
}
