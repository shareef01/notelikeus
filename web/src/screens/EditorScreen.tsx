import {
  ArchiveIcon,
  ArrowBackIcon,
  DockIcon,
  FloatWindowIcon,
  FullscreenIcon,
  ImageIcon,
  NotificationActiveIcon,
  NotificationIcon,
  PinIcon,
} from '@/components/icons/Icons';
import { ChecklistEditor } from '@/components/editor/ChecklistEditor';
import { AttachmentImageStrip } from '@/components/editor/AttachmentImageStrip';
import { EditorBottomBar } from '@/components/editor/EditorBottomBar';
import { useSyncQueue } from '@/hooks/useSyncQueue';
import { EditorOptionsSheet } from '@/components/editor/EditorOptionsSheet';
import { LinkDialog } from '@/components/editor/LinkDialog';
import { EditorContent } from '@tiptap/react';
import { useRichNoteEditor } from '@/hooks/useRichNoteEditor';
import { recordEditorHistoryNoteId, registerEditorBackHandler } from '@/hooks/useEditorHistory';
import { ReminderPickerDialog } from '@/components/editor/ReminderPickerDialog';
import { RichTextToolbar } from '@/components/editor/RichTextToolbar';
import { useNoteEditor } from '@/hooks/useNoteEditor';
import { useImagePasteAndDrop } from '@/hooks/useImagePasteAndDrop';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { isPendingAttachment } from '@/lib/attachments/attachmentPaths';
import { isConflictedCopyTitle } from '@/lib/notes/conflictResolution';
import { useCloudSync } from '@/hooks/useCloudSync';
import { useBodyScrollLock } from '@/hooks/useBodyScrollLock';
import { dismissFocusTrapExcept, useFocusTrap } from '@/hooks/useFocusTrap';
import { useIsTabletUp } from '@/hooks/useMediaQuery';
import { isEditableTarget, useShortcuts } from '@/hooks/useShortcuts';
import { useVisualViewportBottomInset } from '@/hooks/useVisualViewportBottomInset';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import { shareText } from '@/lib/share/shareText';
import { noteSurfaceStyle } from '@/theme/contrast';
import {
  FULLSCREEN_EDITOR_SHELL_CLASS,
  editorWritingColumnClass,
} from '@/screens/main/editorShellLayout';
import { useNotePaletteDark } from '@/theme/useNotePaletteDark';
import { useUiStore, type EditorLayout, type EditorRoute } from '@/store/uiStore';
import { useToastStore } from '@/store/toastStore';
import { useNotesStore } from '@/store/notesStore';
import { saveNote } from '@/lib/notes/noteActions';
import { showUndoToast } from '@/lib/notes/showUndoToast';
import { MAX_NOTE_TITLE_CHARS } from '@/lib/backup/constants';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

const EDITOR_LAYOUTS: {
  id: EditorLayout;
  label: string;
  icon: typeof FloatWindowIcon;
}[] = [
  { id: 'float', label: 'Float note', icon: FloatWindowIcon },
  { id: 'dock', label: 'Dock note', icon: DockIcon },
  { id: 'fullscreen', label: 'Full screen', icon: FullscreenIcon },
];

interface EditorScreenProps {
  route: Exclude<EditorRoute, { mode: 'closed' }>;
}

/**
 * Which layout button to focus once the editor has rebuilt itself.
 *
 * Module scope rather than a ref because a layout change remounts EditorScreen (see the effect
 * that reads this), so anything held inside the component is gone before it can be used.
 */
let pendingLayoutFocusIndex: number | null = null;

function formatNoteForSharing(
  title: string,
  content: string,
  checklist: Array<{ text: string; isChecked: boolean }>,
): string {
  const parts: string[] = [];
  if (title.trim()) {
    parts.push(title.trim());
  }
  if (checklist && checklist.length > 0) {
    const listLines = checklist.map(
      (item) => `- [${item.isChecked ? 'x' : ' '}] ${item.text}`,
    );
    parts.push(listLines.join('\n'));
  } else if (content.trim()) {
    parts.push(content.trim());
  }
  return parts.join('\n\n').trim();
}

export function EditorScreen({ route }: EditorScreenProps) {
  const noteId = route.mode === 'new' ? 'new' : route.noteId;
  const closeEditor = useUiStore((s) => s.closeEditor);
  const editorLayout = useUiStore((s) => s.editorLayout);
  const setEditorLayout = useUiStore((s) => s.setEditorLayout);
  const isTabletUp = useIsTabletUp();
  const keyboardInset = useVisualViewportBottomInset();
  const { userId, isGuest, online } = useCloudSync();
  const editor = useNoteEditor(noteId);
  const { state } = editor;
  const hasPendingAttachments = state.attachments.some((a) => isPendingAttachment(a.storagePath));
  const syncQueue = useSyncQueue();
  const hasUnconfirmedEdit = state.id != null && syncQueue.pendingIds.includes(state.id);

  // Closing on a failed write would discard the only copy of the edit, so navigation is
  // conditional on the local save landing. The failure banner below is then the way out:
  // retry, or discard deliberately.
  const handleBack = useCallback(async () => {
    const result = await editor.flushSave();
    if (result.status === 'failed') return;
    recordEditorHistoryNoteId(result.status === 'saved' ? result.noteId : state.id);
    closeEditor();
  }, [closeEditor, editor, state.id]);

  const handleDiscardAndClose = useCallback(async () => {
    await editor.discardChanges();
    closeEditor();
  }, [closeEditor, editor]);

  const savingRef = useRef(false);

  const handleRetrySave = useCallback(async () => {
    if (savingRef.current) return;
    savingRef.current = true;
    try {
      const result = await editor.flushSave();
      if (result.status === 'saved') {
        useToastStore.getState().show('Note saved');
      } else if (result.status === 'failed') {
        useToastStore
          .getState()
          .show('Could not save this note — your changes are still here', 'error');
      }
    } finally {
      savingRef.current = false;
    }
  }, [editor]);

  const comparingRef = useRef(false);

  const handleCompareVersions = useCallback(async () => {
    if (comparingRef.current) return;
    comparingRef.current = true;
    try {
      const result = await editor.flushSave();
      if (result.status === 'failed') {
        useToastStore
          .getState()
          .show('Could not save this note — your changes are still here', 'error');
        return;
      }
      const targetId =
        (result.status === 'saved' ? result.noteId : undefined) ?? editor.state.id ?? state.id;
      if (targetId) {
        useUiStore.getState().openConflictReview(targetId);
      }
    } finally {
      comparingRef.current = false;
    }
  }, [editor, state.id]);

  useShortcuts([
    {
      key: 'Escape',
      allowInInputs: true,
      action: () => {
        // Docked beside the list, the editor shares the keyboard with it: Escape in the search box
        // is the search box's, not a request to close the note.
        const active = document.activeElement;
        if (dockRef.current && isEditableTarget(active) && !dockRef.current.contains(active)) {
          return false;
        }
        void handleBack();
      },
    },
    {
      key: 'Enter',
      ctrlOrMeta: true,
      allowInInputs: true,
      action: () => {
        const isEmpty =
          !state.title.trim() &&
          !state.content.trim() &&
          state.checklist.length === 0 &&
          state.attachments.length === 0;
        if (isEmpty) return;
        // Only report a save once IndexedDB has taken it, and never let a held-down shortcut
        // stack concurrent saves of a note that has not been assigned an id yet.
        if (savingRef.current) return;
        savingRef.current = true;
        void editor
          .flushSave()
          .then((result) => {
            if (result.status === 'saved') {
              useToastStore.getState().show('Note saved');
            } else if (result.status === 'failed') {
              useToastStore
                .getState()
                .show('Could not save this note — your changes are still here', 'error');
            }
          })
          .finally(() => {
            savingRef.current = false;
          });
      },
    },
  ]);
  const [showOptions, setShowOptions] = useState(false);
  const [showLinkDialog, setShowLinkDialog] = useState(false);
  const [linkTarget, setLinkTarget] = useState<{from: number; to: number; href: string; doc: unknown} | null>(null);
  const [showReminderPicker, setShowReminderPicker] = useState(false);
  const rich = useRichNoteEditor(state.content, (text) => {
    if (/(?:^|\n)\[(?: )?\] $/.test(text)) {
      const result = editor.setContentSmart(text, text.length, text.length);
      if (result.structureChanged) editor.convertContentToChecklist();
    } else editor.setContent(text);
  }, {undo: () => editor.undoConversion(), redo: () => editor.redoConversion()});
  const titleRef = useRef<HTMLTextAreaElement>(null);
  const dockRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const attachmentsEnabled = isR2AttachmentsEnabled();
  const {
    isDragActive,
    handlePaste,
    handleDragEnter,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  } = useImagePasteAndDrop({
    addAttachment: editor.addAttachment,
    attachmentsEnabled,
  });
  const isDarkPalette = useNotePaletteDark();
  const surface = noteSurfaceStyle(0, { solid: true, isDarkPalette });
  const noteAccent = noteSurfaceStyle(state.color, { solid: true, isDarkPalette }).backgroundColor;
  const contentColor = surface.color;
  const hasChecklist = state.checklist.length > 0;
  const isFloatLayout = isTabletUp && editorLayout === 'float';
  const writingColumnClass = editorWritingColumnClass(editorLayout, isTabletUp);
  const isOverlayShell = !isTabletUp || editorLayout === 'fullscreen' || isFloatLayout;
  // Full-window shells need IME lift; float/dock panels sit in a constrained box.
  const effectiveKeyboardInset =
    !isTabletUp || editorLayout === 'fullscreen' ? keyboardInset : 0;

  useEffect(() => {
    const writing = rich.editor;
    if (writing?.isFocused) writing.commands.scrollIntoView();
  }, [effectiveKeyboardInset, rich.editor]);

  useEffect(() => {
    if (noteId !== 'new' || hasChecklist || !rich.editor) return;
    const id = requestAnimationFrame(() => {
      if (titleRef.current && document.activeElement === titleRef.current) return;
      rich.editor?.view.dom.focus();
    });
    return () => cancelAnimationFrame(id);
  }, [noteId, hasChecklist, rich.editor]);

  const focusContentField = () => {
    if (!hasChecklist) rich.editor?.commands.focus();
  };

  useEffect(() => {
    const field = titleRef.current;
    if (!field) return;
    const resize = () => {
      field.style.height = '0px';
      field.style.height = field.scrollHeight + 'px';
    };
    resize();
    let width = field.clientWidth;
    const observer = new ResizeObserver(() => {
      if (field.clientWidth === width) return;
      width = field.clientWidth;
      resize();
    });
    observer.observe(field);
    return () => observer.disconnect();
  }, [state.title]);

  const onFloatClose = useCallback(() => {
    void handleBack();
  }, [handleBack]);

  const needsOverlayTrap = !isTabletUp || editorLayout === 'fullscreen';
  const floatPanelRef = useFocusTrap<HTMLDivElement>(isFloatLayout, onFloatClose);
  const overlayPanelRef = useFocusTrap<HTMLDivElement>(needsOverlayTrap, onFloatClose);
  useBodyScrollLock(isOverlayShell);
  useEffect(() => registerEditorBackHandler(async () => {
    if (dismissFocusTrapExcept(floatPanelRef.current, overlayPanelRef.current)) return;
    await handleBack();
  }), [handleBack, floatPanelRef, overlayPanelRef]);

  const handleDelete = async () => {
    // Trashing is a write like any other; closing on a failed one would report a delete that
    // never happened and leave the note where it was.
    const result = await editor.trashNote();
    if (result.status === 'failed') {
      useToastStore.getState().show('Could not move this note to trash', 'error');
      return;
    }
    // Trashing from a card offers Undo; trashing from the editor has to as well, or the same
    // action is reversible in one place and not the other.
    const trashed = useNotesStore.getState().notes.find((note) => note.id === state.id);
    if (trashed) {
      showUndoToast({
        message: 'Note moved to trash',
        revert: () => saveNote({ ...trashed, isTrashed: false }),
      });
    }
    closeEditor();
  };

  const handleShareNote = async () => {
    const text = formatNoteForSharing(state.title, state.content, state.checklist);
    if (!text) return;
    await shareText({ title: state.title || 'Note', text });
  };

  const handleExportMarkdown = () => {
    const text = formatNoteForSharing(state.title, state.content, state.checklist);
    if (!text) return;
    const safeTitle = (state.title || 'note').replace(/[^a-z0-9-_]/gi, '_').toLowerCase() || 'note';
    const filename = `${safeTitle}.md`;
    const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    useToastStore.getState().show(`Exported ${filename}`);
  };

  const dropOverlay = isDragActive ? (
    <div
      data-testid="editor-drop-overlay"
      className="absolute inset-0 z-30 pointer-events-none flex flex-col items-center justify-center rounded-inherit border-2 border-dashed border-brand-primary/60 bg-brand-surface/85 backdrop-blur-[2px] transition-opacity duration-150 animate-in fade-in"
      aria-hidden="true"
    >
      <div
        className="flex items-center gap-2.5 rounded-full bg-brand-surface px-4 py-2 text-sm font-medium shadow-lg border border-brand-outline/40"
        style={{ color: contentColor }}
      >
        <ImageIcon size={20} />
        <span>Drop image to attach</span>
      </div>
    </div>
  ) : null;

  const editorShell = (children: ReactNode) => {
    if (!isTabletUp) {
      return (
        <div
          className="fixed inset-0 z-40 flex flex-col bg-black/40"
          onPaste={handlePaste}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={(e) => void handleDrop(e)}
        >
          <div
            ref={overlayPanelRef}
            role="dialog"
            aria-modal="true"
            aria-label="Note editor"
            className="relative flex h-full w-full flex-col"
            data-editor-surface="" style={surface}
          >
            {dropOverlay}
            {children}
          </div>
        </div>
      );
    }

    if (editorLayout === 'dock') {
      return (
        <div
          ref={dockRef}
          className="relative flex h-full w-full flex-col"
          onPaste={handlePaste}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={(e) => void handleDrop(e)}
        >
          <div className="relative flex h-full w-full flex-col" data-editor-surface="" style={surface}>
            {dropOverlay}
            {children}
          </div>
        </div>
      );
    }

    if (editorLayout === 'fullscreen') {
      return (
        <div
          ref={overlayPanelRef}
          role="dialog"
          aria-modal="true"
          aria-label="Note editor"
          className={FULLSCREEN_EDITOR_SHELL_CLASS}
          data-editor-surface="" style={surface}
          onPaste={handlePaste}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={(e) => void handleDrop(e)}
        >
          {dropOverlay}
          <div className="relative flex h-full w-full flex-col">{children}</div>
        </div>
      );
    }

    return (
      <div
        className="fixed inset-0 z-40 flex items-center justify-center bg-black/45 p-4 sm:p-6"
        onClick={onFloatClose}
        onPaste={handlePaste}
        onDragEnter={handleDragEnter}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={(e) => void handleDrop(e)}
      >
        <div
          ref={floatPanelRef}
          className="relative flex h-[min(52rem,90vh)] w-full max-w-editor flex-col overflow-hidden rounded-note border border-brand-outline/40 shadow-2xl animate-in fade-in zoom-in-95 duration-200"
          data-editor-surface="" style={surface}
          onClick={(event) => event.stopPropagation()}
          role="dialog"
          aria-modal="true"
          aria-label="Note editor"
        >
          {dropOverlay}
          {children}
        </div>
      </div>
    );
  };

  // A radiogroup is a single tab stop whose selection moves with the arrow keys. The markup
  // already claimed those roles, so without the roving tabindex and key handling below the
  // control announced a contract that keyboard users could not actually drive.
  const layoutRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const selectLayoutAt = (index: number) => {
    const bounded = (index + EDITOR_LAYOUTS.length) % EDITOR_LAYOUTS.length;
    pendingLayoutFocusIndex = bounded;
    setEditorLayout(EDITOR_LAYOUTS[bounded].id);
  };

  // Restoring focus after an arrow key has to survive a remount, which is why the intent is
  // held outside the component. MainScreen renders the editor from two different branches —
  // docked and overlay — so changing the layout moves it in the tree and React unmounts and
  // rebuilds the whole screen. Every ref and piece of local state is new by the time this runs,
  // and the rebuilt screen focuses the note body, so a keyboard user pressing ArrowRight saw
  // selection move while focus jumped out of the control entirely.
  //
  // The rAF matters too: it lands after the remounted screen's own focus effect, which would
  // otherwise win.
  useEffect(() => {
    const target = pendingLayoutFocusIndex;
    if (target == null) return;
    pendingLayoutFocusIndex = null;
    const id = requestAnimationFrame(() => layoutRefs.current[target]?.focus());
    return () => cancelAnimationFrame(id);
  }, [editorLayout]);

  const layoutControls = isTabletUp ? (
    <div
      className="flex shrink-0 items-center gap-0.5 rounded-xl border border-brand-outline/50 bg-true-surface-variant/40 p-0.5"
      role="radiogroup"
      aria-label="Editor layout"
    >
      {EDITOR_LAYOUTS.map((button, index) => {
        const active = editorLayout === button.id;
        const Icon = button.icon;
        return (
          <button
            key={button.id}
            ref={(node) => {
              layoutRefs.current[index] = node;
            }}
            type="button"
            role="radio"
            aria-checked={active}
            tabIndex={active ? 0 : -1}
            onClick={() => setEditorLayout(button.id)}
            onKeyDown={(event) => {
              switch (event.key) {
                case 'ArrowRight':
                case 'ArrowDown':
                  event.preventDefault();
                  selectLayoutAt(index + 1);
                  break;
                case 'ArrowLeft':
                case 'ArrowUp':
                  event.preventDefault();
                  selectLayoutAt(index - 1);
                  break;
                case 'Home':
                  event.preventDefault();
                  selectLayoutAt(0);
                  break;
                case 'End':
                  event.preventDefault();
                  selectLayoutAt(EDITOR_LAYOUTS.length - 1);
                  break;
                default:
                  break;
              }
            }}
            className={`flex h-8 w-8 items-center justify-center gap-1.5 rounded-lg text-xs font-medium transition-colors duration-150 min-[1200px]:w-auto min-[1200px]:px-2.5 ${CHROME_FOCUS} ${
              active
                ? 'bg-true-surface shadow-xs'
                : 'text-brand-secondary hover:bg-brand-primary/5'
            }`}
            aria-label={button.label}
            title={button.label}
          >
            <Icon size={18} />
            <span className="hidden min-[1200px]:inline">{button.id === 'float' ? 'Float' : button.id === 'dock' ? 'Dock' : 'Full screen'}</span>
          </button>
        );
      })}
    </div>
  ) : null;


  return editorShell(
    <>
      <header
        className="flex shrink-0 items-center justify-between gap-2 border-b border-brand-outline/40 px-3 pb-2 pt-safe"
        style={{ color: contentColor, borderTop: `3px solid ${state.color === 0 ? 'transparent' : noteAccent}` }}
      >
        <button
          type="button"
          onClick={() => void handleBack()}
          className={`flex h-11 shrink-0 items-center justify-center gap-2 rounded-xl px-2 text-sm font-medium hover:bg-brand-primary/5 ${CHROME_FOCUS}`}
          aria-label="Back"
        >
          <ArrowBackIcon size={20} />
          <span className="hidden min-[1200px]:inline">Back</span>
        </button>

        <div className="flex items-center gap-1">
          {layoutControls}
          {isTabletUp ? <span className="mx-1 h-5 w-px bg-brand-outline/50" aria-hidden /> : null}
          <button
            type="button"
            onClick={() => setShowReminderPicker(true)}
            className={`flex size-10 items-center justify-center rounded-xl hover:bg-brand-primary/5 ${CHROME_FOCUS}`}
            aria-label="Set reminder"
            title="Set reminder"
          >
            {state.reminderTimestamp != null ? (
              <NotificationActiveIcon size={20} className="opacity-100" />
            ) : (
              <NotificationIcon size={20} className="opacity-80" />
            )}
          </button>
          <button
            type="button"
            onClick={editor.togglePin}
            className={`flex size-10 items-center justify-center rounded-xl hover:bg-brand-primary/5 ${CHROME_FOCUS}`}
            aria-pressed={state.isPinned}
            aria-label={state.isPinned ? 'Unpin' : 'Pin'}
            title={state.isPinned ? 'Unpin note' : 'Pin note'}
          >
            <PinIcon size={20} className={state.isPinned ? 'opacity-100' : 'opacity-80'} />
          </button>
          <button
            type="button"
            onClick={editor.toggleArchive}
            className={`flex size-10 items-center justify-center rounded-xl hover:bg-brand-primary/5 ${CHROME_FOCUS}`}
            aria-pressed={state.isArchived}
            aria-label={state.isArchived ? 'Unarchive' : 'Archive'}
            title={state.isArchived ? 'Unarchive note' : 'Archive note'}
          >
            <ArchiveIcon size={20} className={state.isArchived ? 'opacity-100' : 'opacity-80'} />
          </button>
        </div>
      </header>

      <div
        className="flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain px-layout-gap pt-7 sm:px-8 sm:pt-10 lg:px-10"
        style={{
          // Shrink the scrollport above the keyboard. Padding alone extends the
          // content but leaves the caret's visibility boundary behind the keyboard.
          marginBottom: effectiveKeyboardInset,
          paddingBottom: 'calc(5.5rem + max(env(safe-area-inset-bottom, 0px), 1rem))',
          scrollPaddingBlock: '4rem 6rem',
        }}
        onClick={(event) => {
          const target = event.target as HTMLElement;
          if (
            target.closest(
              'input, textarea, [contenteditable], button, a, [aria-label="Text formatting"], [role="toolbar"]',
            )
          ) {
            return;
          }
          focusContentField();
        }}
      >
        <div className={['flex w-full flex-col', writingColumnClass].filter(Boolean).join(' ')}>
          {state.conflictCopy || isConflictedCopyTitle(state.title) ? (
            <div
              role="status"
              className="mb-4 flex flex-col gap-2 rounded-note border border-amber-500/40 bg-amber-500/10 p-3 text-sm sm:flex-row sm:items-center sm:justify-between"
              style={{ color: contentColor }}
            >
              <span>
                This note was kept from a sync conflict so neither version was lost.
              </span>
              {state.id ? (
                <button
                  type="button"
                  onClick={() => void handleCompareVersions()}
                  className={`shrink-0 rounded-full border border-amber-500/50 bg-amber-500/20 px-3 py-1.5 text-xs font-semibold tracking-wide hover:bg-amber-500/30 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary ${CHROME_FOCUS}`}
                  aria-label="Compare with the current note"
                >
                  Compare versions
                </button>
              ) : null}
            </div>
          ) : null}
          {state.saveFailed ? (
            <div
              role="alert"
              className="mb-4 flex flex-col gap-2 rounded-note border border-red-500/40 bg-red-500/10 p-3 text-sm sm:flex-row sm:items-center sm:justify-between"
              style={{ color: contentColor }}
            >
              <span>Could not save this note. Your changes are still here.</span>
              <span className="flex shrink-0 gap-2">
                <button
                  type="button"
                  onClick={() => void handleRetrySave()}
                  className={`rounded-full bg-[color-mix(in_srgb,currentColor_14%,transparent)] px-3 py-1.5 font-medium ${CHROME_FOCUS}`}
                >
                  Retry save
                </button>
                <button
                  type="button"
                  onClick={() => void handleDiscardAndClose()}
                  className={`rounded-full px-3 py-1.5 font-medium underline underline-offset-2 ${CHROME_FOCUS}`}
                >
                  Discard changes
                </button>
              </span>
            </div>
          ) : null}

          <label htmlFor="note-title" className="sr-only">
            Note title
          </label>
          <textarea
            ref={titleRef}
            id="note-title"
            rows={1}
            value={state.title}
            onChange={(event) => editor.setTitle(event.target.value.replace(/[\r\n]+/g, ' '))}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                event.preventDefault();
                focusContentField();
              }
            }}
            placeholder="Title"
            maxLength={MAX_NOTE_TITLE_CHARS}
            className="w-full resize-none overflow-hidden bg-transparent text-[26px] font-semibold leading-tight tracking-[-0.03em] outline-none placeholder:text-current placeholder:opacity-80 sm:text-[32px]"
            style={{ color: contentColor }}
          />

          {state.labels.length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-2">
              {state.labels.map((label) => (
                <span
                  key={label.id}
                  className="rounded-full px-2.5 py-1 text-xs font-medium tracking-wide"
                  style={{ backgroundColor: 'color-mix(in srgb, currentColor 10%, transparent)', color: contentColor }}
                >
                  {label.name}
                </span>
              ))}
            </div>
          ) : null}

          {state.attachments.length > 0 ? (
            <AttachmentImageStrip
              noteId={state.id ?? 'draft'}
              attachments={state.attachments}
              contentColor={contentColor}
              onRemove={editor.removeAttachment}
            />
          ) : null}

          {(editor.canUndoConversion || editor.canRedoConversion) && <button type="button"
            className={`mt-3 self-start rounded-note px-3 py-2 text-sm text-brand-primary ${CHROME_FOCUS}`}
            onClick={() => editor.canUndoConversion ? editor.undoConversion() : editor.redoConversion()}>
            {editor.canUndoConversion ? 'Undo conversion' : 'Redo conversion'}
          </button>}
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
              <RichTextToolbar
                contentColor={contentColor}
                backgroundColor="rgb(var(--surface-variant-rgb))"
                active={rich.active ?? undefined}
                onBold={() => rich.editor?.chain().focus().toggleBold().run()}
                onItalic={() => rich.editor?.chain().focus().toggleItalic().run()}
                onBullet={() => rich.editor?.chain().focus().toggleBulletList().run()}
                onChecklist={() => editor.convertContentToChecklist(rich.editor?.getText({ blockSeparator: '\n' }))}
                onLink={() => {
                  if (!rich.editor) return;
                  const {from, to} = rich.editor.state.selection;
                  setLinkTarget({from, to, href: rich.editor.isActive('link') ? rich.editor.getAttributes('link').href : '', doc: rich.editor.state.doc});
                  setShowLinkDialog(true);
                }}
                onAddImage={
                  attachmentsEnabled
                    ? () => {
                        fileInputRef.current?.click();
                      }
                    : undefined
                }
              />

              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = '';
                  if (file) void editor.addAttachment(file);
                }}
              />

              <EditorContent editor={rich.editor} className="mt-6" style={{ color: contentColor }} />

              {/* A cue for starting a list in an empty note. Once the note has text, the toolbar's
                  Checklist button converts it; a second "Convert to checklist" here repeated that
                  button below the fold, under the footer (U-10). */}
              {!state.content.trim() ? (
                <button
                  type="button"
                  onClick={() => editor.convertContentToChecklist()}
                  className={`tap-target mt-10 self-start text-left text-sm font-medium opacity-80 transition-opacity hover:opacity-100 ${CHROME_FOCUS}`}
                  style={{ color: contentColor }}
                >
                  + Add checklist
                </button>
              ) : null}
            </>
          )}
        </div>
      </div>

      <div
        className="absolute inset-x-0 bottom-0 z-20 pointer-events-none"
        style={{ color: contentColor, bottom: effectiveKeyboardInset }}
      >
        <div
          className="pointer-events-auto"
          style={{
            background: surface.backgroundColor,
          }}
        >
          <EditorBottomBar
            timestamp={state.timestamp}
            isSaving={state.isSaving || editor.isDirty}
            saveFailed={state.saveFailed}
            isSavedLocally={state.lastSavedAt != null}
            isSignedIn={Boolean(userId) && !isGuest}
            isOnline={online}
            isPending={hasPendingAttachments || hasUnconfirmedEdit}
            isFlushing={syncQueue.isFlushing}
            flushFailed={syncQueue.flushFailed}
            contentColor={contentColor}
            reminderTimestamp={state.reminderTimestamp}
            onRetrySave={() => void handleRetrySave()}
            onMoreClick={() => setShowOptions(true)}
          />
        </div>
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
        onDeleteNote={() => void handleDelete()}
        onShareNote={() => void handleShareNote()}
        onExportMarkdown={handleExportMarkdown}
      />
      <LinkDialog
        open={showLinkDialog}
        onCancel={() => setShowLinkDialog(false)}
        initialUrl={linkTarget?.href ?? ''}
        onRemove={() => {
          if (rich.editor && linkTarget && rich.editor.state.doc === linkTarget.doc) {
            rich.editor.chain().focus().setTextSelection({from: linkTarget.from, to: linkTarget.to}).extendMarkRange('link').unsetLink().run();
          }
          setShowLinkDialog(false);
        }}
        onConfirm={(href) => {
          if (rich.editor && linkTarget && rich.editor.state.doc === linkTarget.doc) {
            const chain = rich.editor.chain().focus().setTextSelection({from: linkTarget.from, to: linkTarget.to});
            if (linkTarget.href) chain.extendMarkRange('link').setLink({href}).run();
            else if (linkTarget.from === linkTarget.to) chain.insertContent({ type: 'text', text: 'link', marks: [{ type: 'link', attrs: { href } }] }).run();
            else chain.setLink({href}).run();
          } else useToastStore.getState().show('The note changed. Select the text and try again.', 'error');
          setShowLinkDialog(false);
        }}
      />

      <ReminderPickerDialog
        open={showReminderPicker}
        initialTimestamp={state.reminderTimestamp}
        onCancel={() => setShowReminderPicker(false)}
        onConfirm={(timestamp) => {
          editor.setReminderTimestamp(timestamp);
          setShowReminderPicker(false);
        }}
        onRemove={() => {
          editor.clearReminder();
          setShowReminderPicker(false);
        }}
      />
    </>,
  );
}
