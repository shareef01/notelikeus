import { ArchiveIcon, ArrowBackIcon, LockIcon, PinIcon } from '@/components/icons/Icons';
import { ChecklistEditor } from '@/components/editor/ChecklistEditor';
import { EditorBottomBar } from '@/components/editor/EditorBottomBar';
import { EditorOptionsSheet } from '@/components/editor/EditorOptionsSheet';
import { LinkDialog } from '@/components/editor/LinkDialog';
import { RichTextToolbar } from '@/components/editor/RichTextToolbar';
import { useNoteEditor } from '@/hooks/useNoteEditor';
import {
  prefixLinesWithBullet,
  wrapSelection,
  wrapSelectionAsLink,
} from '@/lib/text/markdown';
import { contentColorForBackground, noteSurfaceStyle } from '@/theme/contrast';
import { useToastStore } from '@/store/toastStore';
import { useRef, useState, type ReactNode } from 'react';
import { useParams, useNavigate } from 'react-router-dom';

interface EditorScreenProps {
  mode: 'new' | 'edit';
}

export function EditorScreen({ mode }: EditorScreenProps) {
  const { id } = useParams();
  const navigate = useNavigate();
  const noteId = mode === 'new' ? 'new' : (id || 'new');

  const editor = useNoteEditor(noteId);
  const { state } = editor;
  const [showOptions, setShowOptions] = useState(false);
  const [showLinkDialog, setShowLinkDialog] = useState(false);
  const [hasTextSelection, setHasTextSelection] = useState(false);
  const contentRef = useRef<HTMLTextAreaElement>(null);

  const surface = noteSurfaceStyle(state.color);
  const contentColor = contentColorForBackground(state.color);
  const showLockOverlay = state.isLocked && !state.isAccessGranted;
  const hasChecklist = state.checklist.length > 0;

  const updateTextSelection = () => {
    const field = contentRef.current;
    if (!field) return;
    setHasTextSelection(field.selectionStart !== field.selectionEnd);
  };

  const applyFormatting = (
    updater: (
      text: string,
      selectionStart: number,
      selectionEnd: number,
    ) => { text: string; selectionStart: number; selectionEnd: number } | null,
  ) => {
    const field = contentRef.current;
    if (!field) return;
    const result = editor.applyContentFormatting(
      updater,
      field.selectionStart,
      field.selectionEnd,
    );
    if (!result) return;
    requestAnimationFrame(() => {
      field.focus();
      field.setSelectionRange(result.selectionStart, result.selectionEnd);
      updateTextSelection();
    });
  };

  const handleBack = async () => {
    try {
      await editor.flushSave();
    } catch {
      // Save failed — navigate back anyway
    }
    navigate('/', { replace: true });
  };

  const handleDelete = async () => {
    await editor.trashNote();
    navigate('/', { replace: true });
  };

  const handleTogglePin = () => {
    const wasPinned = state.isPinned;
    editor.togglePin();
    useToastStore.getState().show(wasPinned ? 'Note unpinned' : 'Note pinned');
  };

  const handleToggleArchive = () => {
    const wasArchived = state.isArchived;
    editor.toggleArchive();
    useToastStore.getState().show(wasArchived ? 'Note unarchived' : 'Note archived');
  };

  const handleAddLink = (url: string) => {
    setShowLinkDialog(false);
    applyFormatting((text, start, end) => wrapSelectionAsLink(text, start, end, url));
  };

  const editorShell = (children: ReactNode) => {
    return (
      <div className="fixed inset-0 z-50 flex flex-col bg-[#121214] animate-in fade-in duration-300" style={{ height: '100dvh' }}>
        <div className="relative flex h-full w-full flex-col" style={surface}>
          {children}
        </div>
      </div>
    );
  };



  if (showLockOverlay) {

    return editorShell(

      <>

        <header className="flex items-center px-2 pt-safe">

          <button
            type="button"
            onClick={() => navigate('/', { replace: true })}
            className="flex size-11 items-center justify-center rounded-full note-surface-hover"

            style={{ color: contentColor }}

            aria-label="Back"

          >

            <ArrowBackIcon size={22} />

          </button>

        </header>

        <div className="flex flex-1 flex-col items-center justify-center gap-4 px-8 text-center">

          <div style={{ color: contentColor }}>

            <LockIcon size={56} className="opacity-60" />

          </div>

          <p className="text-xl font-semibold sm:text-2xl" style={{ color: contentColor }}>

            This note is locked

          </p>

          <p className="max-w-sm text-sm opacity-70" style={{ color: contentColor }}>

            Unlock to view and edit on this device. Locked notes are kept local and are not synced to the cloud.

          </p>

          <button

            type="button"

            onClick={editor.grantAccess}

            className="rounded-note note-surface-cta px-6 py-3 text-sm font-semibold backdrop-blur-sm"

            style={{ color: contentColor }}

          >

            Unlock

          </button>

        </div>

      </>,

    );

  }



  return editorShell(

    <>

      <header

        className="flex items-center justify-between px-1 pt-safe"

        style={{ color: contentColor }}

      >

        <button

          type="button"

          onClick={() => void handleBack()}

          className="flex size-11 items-center justify-center rounded-full note-surface-hover"

          aria-label="Back"

        >

          <ArrowBackIcon size={22} />

        </button>

        <div className="flex items-center gap-1">

          <button

            type="button"

            onClick={handleTogglePin}

            className="flex size-11 items-center justify-center rounded-full note-surface-hover"

            aria-label={state.isPinned ? 'Unpin' : 'Pin'}

          >

            <PinIcon size={20} className={state.isPinned ? 'opacity-100' : 'opacity-55'} />

          </button>

          <button

            type="button"

            onClick={handleToggleArchive}

            className="flex size-11 items-center justify-center rounded-full note-surface-hover"

            aria-label={state.isArchived ? 'Unarchive' : 'Archive'}

          >

            <ArchiveIcon size={20} className={state.isArchived ? 'opacity-100' : 'opacity-55'} />

          </button>

        </div>

      </header>



      <div className="flex-1 overflow-y-auto px-4 pb-28 pt-4 sm:px-6 sm:pt-6">
        <input
          type="text"
          value={state.title}
          onChange={(event) => editor.setTitle(event.target.value)}
          placeholder="Title"
          className="w-full bg-transparent text-[20px] font-bold tracking-tight outline-none placeholder:text-current placeholder:opacity-30 focus-visible:outline-none sm:text-[24px]"
          style={{ color: contentColor }}
        />



        {state.labels.length > 0 ? (

          <div className="mt-3 flex flex-wrap gap-2">

            {state.labels.map((label) => (

              <span
                key={label.id}
                className="rounded-full px-2.5 py-1 text-xs font-medium note-surface-chip"
                style={{ color: contentColor }}
              >
                {label.name}
              </span>

            ))}

          </div>

        ) : null}



        {hasChecklist ? (

          <ChecklistEditor

            items={state.checklist}

            contentColor={contentColor}

            onUpdate={editor.updateChecklistItem}

            onAdd={editor.addChecklistItem}

            onRemove={editor.removeChecklistItem}

            onConvertToText={editor.convertChecklistToContent}

          />

        ) : (

          <>

            {hasTextSelection ? (

              <RichTextToolbar

                contentColor={contentColor}

                onBold={() =>

                  applyFormatting((text, start, end) => wrapSelection(text, start, end, '**'))

                }

                onItalic={() =>

                  applyFormatting((text, start, end) => wrapSelection(text, start, end, '_'))

                }

                onBullet={() =>

                  applyFormatting((text, start, end) => prefixLinesWithBullet(text, start, end))

                }

                onLink={() => setShowLinkDialog(true)}

              />

            ) : null}

            <textarea
              ref={contentRef}
              value={state.content}
              onChange={(event) => {
                const field = event.target;
                const result = editor.setContentSmart(
                  field.value,
                  field.selectionStart,
                  field.selectionEnd,
                );
                if (result.structureChanged) {
                  editor.convertContentToChecklist();
                  return;
                }
                requestAnimationFrame(() => {
                  field.setSelectionRange(result.selectionStart, result.selectionEnd);
                  updateTextSelection();
                });
              }}
              onSelect={updateTextSelection}
              onKeyUp={updateTextSelection}
              onMouseUp={updateTextSelection}
              placeholder="Note"
              rows={12}
              className="mt-3 w-full resize-none bg-transparent text-[15px] leading-[1.6em] outline-none placeholder:text-current placeholder:opacity-30 focus-visible:outline-none sm:mt-4 sm:text-[17px] sm:leading-[1.65em]"
              style={{ color: contentColor }}
            />

            {state.content.trim() ? (
              <button
                type="button"
                onClick={editor.convertContentToChecklist}
                className="mt-6 text-sm font-bold uppercase tracking-wider opacity-60 hover:opacity-100 transition-opacity"
                style={{ color: contentColor }}
              >
                Convert to checklist
              </button>
            ) : (
              <button
                type="button"
                onClick={editor.convertContentToChecklist}
                className="mt-8 text-sm font-bold uppercase tracking-wider opacity-60 hover:opacity-100 transition-opacity"
                style={{ color: contentColor }}
              >
                + Add checklist
              </button>
            )}

          </>

        )}

      </div>



      <div className="absolute inset-x-0 bottom-0" style={{ color: contentColor }}>

        <EditorBottomBar

          timestamp={state.timestamp}

          isSaving={state.isSaving}

          contentColor={contentColor}

          contentLength={state.content.length}

          reminderTimestamp={state.reminderTimestamp}

          onMoreClick={() => setShowOptions(true)}

        />

      </div>



      <EditorOptionsSheet

        open={showOptions}

        onClose={() => setShowOptions(false)}

        selectedColor={state.color}

        onColorSelect={editor.setColor}

        allLabels={editor.allLabels}

        selectedLabels={state.labels}

        onLabelToggle={editor.toggleLabel}

        onCreateLabel={editor.createLabel}

        reminderTimestamp={state.reminderTimestamp}

        onReminderChange={editor.setReminderTimestamp}

        isLocked={state.isLocked}

        onLockToggle={editor.toggleLock}

        onDeleteNote={() => void handleDelete()}

      />



      <LinkDialog

        open={showLinkDialog}

        onCancel={() => setShowLinkDialog(false)}

        onConfirm={handleAddLink}

      />

    </>,

  );

}

