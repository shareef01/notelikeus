import {
  ArchiveIcon,
  ArrowBackIcon,
  DockIcon,
  FloatWindowIcon,
  FullscreenIcon,
  NotificationActiveIcon,
  NotificationIcon,
  PinIcon,
} from '@/components/icons/Icons';
import { ChecklistEditor } from '@/components/editor/ChecklistEditor';
import { AttachmentImageStrip } from '@/components/editor/AttachmentImageStrip';
import { EditorBottomBar } from '@/components/editor/EditorBottomBar';
import { EditorOptionsSheet } from '@/components/editor/EditorOptionsSheet';
import { LinkDialog } from '@/components/editor/LinkDialog';
import { MarkdownBody } from '@/components/editor/MarkdownPreview';
import { ReminderPickerDialog } from '@/components/editor/ReminderPickerDialog';
import { RichTextToolbar } from '@/components/editor/RichTextToolbar';
import { useNoteEditor } from '@/hooks/useNoteEditor';
import { isR2AttachmentsEnabled } from '@/lib/attachments/attachmentConfig';
import { useBodyScrollLock } from '@/hooks/useBodyScrollLock';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import { useIsTabletUp } from '@/hooks/useMediaQuery';
import { useShortcuts } from '@/hooks/useShortcuts';
import { useVisualViewportBottomInset } from '@/hooks/useVisualViewportBottomInset';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import {
  prefixLinesWithBullet,
  wrapSelection,
  wrapSelectionAsLink,
} from '@/lib/text/markdown';
import { shareText } from '@/lib/share/shareText';
import { noteSurfaceStyle } from '@/theme/contrast';
import {
  FULLSCREEN_EDITOR_SHELL_CLASS,
  editorWritingColumnClass,
} from '@/screens/main/editorShellLayout';
import { useNotePaletteDark } from '@/theme/useNotePaletteDark';
import { useUiStore, type EditorLayout, type EditorRoute } from '@/store/uiStore';
import { useToastStore } from '@/store/toastStore';
import { MAX_NOTE_CONTENT_CHARS, MAX_NOTE_TITLE_CHARS } from '@/lib/backup/constants';
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
  const editor = useNoteEditor(noteId);
  const { state } = editor;

  // Closing on a failed write would discard the only copy of the edit, so navigation is
  // conditional on the local save landing. The failure banner below is then the way out:
  // retry, or discard deliberately.
  const handleBack = useCallback(async () => {
    const result = await editor.flushSave();
    if (result.status === 'failed') return;
    closeEditor();
  }, [closeEditor, editor]);

  const handleDiscardAndClose = useCallback(async () => {
    await editor.discardChanges();
    closeEditor();
  }, [closeEditor, editor]);

  const savingRef = useRef(false);

  useShortcuts([
    {
      key: 'Escape',
      allowInInputs: true,
      action: () => {
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
  const [showReminderPicker, setShowReminderPicker] = useState(false);
  const [contentFocused, setContentFocused] = useState(true);
  const contentRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const selectionRef = useRef({ start: 0, end: 0 });
  const attachmentsEnabled = isR2AttachmentsEnabled();
  const isDarkPalette = useNotePaletteDark();
  const surface = noteSurfaceStyle(state.color, { solid: true, isDarkPalette });
  const contentColor =
    state.color === 0 ? 'rgb(var(--primary-rgb))' : surface.color;
  const hasChecklist = state.checklist.length > 0;
  const isFloatLayout = isTabletUp && editorLayout === 'float';
  const writingColumnClass = editorWritingColumnClass(editorLayout, isTabletUp);
  const isOverlayShell = !isTabletUp || editorLayout === 'fullscreen' || isFloatLayout;
  // Full-window shells need IME lift; float/dock panels sit in a constrained box.
  const effectiveKeyboardInset =
    !isTabletUp || editorLayout === 'fullscreen' ? keyboardInset : 0;

  useEffect(() => {
    const field = contentRef.current;
    if (!field || !contentFocused || hasChecklist) return;
    field.style.height = '0px';
    field.style.height = `${Math.max(field.scrollHeight, 240)}px`;
  }, [state.content, contentFocused, hasChecklist]);

  useEffect(() => {
    if (noteId !== 'new' || hasChecklist) return;
    setContentFocused(true);
    const id = requestAnimationFrame(() => {
      contentRef.current?.focus();
    });
    return () => cancelAnimationFrame(id);
  }, [noteId, hasChecklist]);

  const focusContentField = () => {
    if (hasChecklist) return;
    setContentFocused(true);
    requestAnimationFrame(() => contentRef.current?.focus());
  };

  const rememberSelection = () => {
    const field = contentRef.current;
    if (!field) return;
    selectionRef.current = {
      start: field.selectionStart,
      end: field.selectionEnd,
    };
  };

  const applyFormatting = (
    updater: (
      text: string,
      selectionStart: number,
      selectionEnd: number,
    ) => { text: string; selectionStart: number; selectionEnd: number } | null,
  ) => {
    setContentFocused(true);

    const run = () => {
      const field = contentRef.current;
      // Textarea keeps selection offsets after blur; prefer them when mounted.
      const start = field ? field.selectionStart : selectionRef.current.start;
      const end = field ? field.selectionEnd : selectionRef.current.end;

      const result = editor.applyContentFormatting(updater, start, end);
      if (!result) return;

      selectionRef.current = {
        start: result.selectionStart,
        end: result.selectionEnd,
      };
      setContentFocused(true);

      requestAnimationFrame(() => {
        const nextField = contentRef.current;
        if (!nextField) return;
        nextField.focus();
        nextField.setSelectionRange(result.selectionStart, result.selectionEnd);
      });
    };

    if (contentRef.current) {
      run();
    } else {
      requestAnimationFrame(run);
    }
  };

  const onFloatClose = useCallback(() => {
    void handleBack();
  }, [handleBack]);

  const needsOverlayTrap = !isTabletUp || editorLayout === 'fullscreen';
  const floatPanelRef = useFocusTrap<HTMLDivElement>(isFloatLayout, onFloatClose);
  const overlayPanelRef = useFocusTrap<HTMLDivElement>(needsOverlayTrap, onFloatClose);
  useBodyScrollLock(isOverlayShell);

  const handleDelete = async () => {
    // Trashing is a write like any other; closing on a failed one would report a delete that
    // never happened and leave the note where it was.
    const result = await editor.trashNote();
    if (result.status === 'failed') {
      useToastStore.getState().show('Could not move this note to trash', 'error');
      return;
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

  const editorShell = (children: ReactNode) => {
    if (!isTabletUp) {
      return (
        <div className="fixed inset-0 z-40 flex flex-col bg-black/40">
          <div
            ref={overlayPanelRef}
            role="dialog"
            aria-modal="true"
            aria-label="Note editor"
            className="relative flex h-full w-full flex-col"
            style={surface}
          >
            {children}
          </div>
        </div>
      );
    }

    if (editorLayout === 'dock') {
      return (
        <div className="relative flex h-full w-full flex-col">
          <div className="relative mx-auto flex h-full w-full max-w-editor flex-col" style={surface}>
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
          style={surface}
        >
          <div className="relative flex h-full w-full flex-col">{children}</div>
        </div>
      );
    }

    return (
      <div
        className="fixed inset-0 z-40 flex items-center justify-center bg-black/45 p-4 sm:p-6"
        onClick={onFloatClose}
      >
        <div
          ref={floatPanelRef}
          className="relative flex h-[min(52rem,90vh)] w-full max-w-editor flex-col overflow-hidden rounded-note border border-brand-outline/40 shadow-2xl animate-in fade-in zoom-in-95 duration-200"
          style={surface}
          onClick={(event) => event.stopPropagation()}
          role="dialog"
          aria-modal="true"
          aria-label="Note editor"
        >
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
      className="flex shrink-0 items-center"
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
            className={`flex size-9 items-center justify-center rounded-full transition-opacity duration-150 ${CHROME_FOCUS} ${
              active
                ? 'opacity-100'
                : 'opacity-45 hover:bg-[color-mix(in_srgb,currentColor_8%,transparent)] hover:opacity-80'
            }`}
            aria-label={button.label}
            title={button.label}
          >
            <Icon size={18} />
          </button>
        );
      })}
    </div>
  ) : null;


  return editorShell(
    <>
      <header
        className="flex shrink-0 items-center justify-between px-2 pt-safe sm:px-3 lg:px-4"
        style={{ color: contentColor }}
      >
        <button
          type="button"
          onClick={() => void handleBack()}
          className={`flex size-11 items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)] ${CHROME_FOCUS}`}
          aria-label="Back"
        >
          <ArrowBackIcon size={22} />
        </button>

        <div className="flex items-center gap-1">
          {layoutControls}
          <button
            type="button"
            onClick={() => setShowReminderPicker(true)}
            className={`flex size-11 items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)] ${CHROME_FOCUS}`}
            aria-label="Set reminder"
          >
            {state.reminderTimestamp != null ? (
              <NotificationActiveIcon size={20} className="opacity-100" />
            ) : (
              <NotificationIcon size={20} className="opacity-55" />
            )}
          </button>
          <button
            type="button"
            onClick={editor.togglePin}
            className={`flex size-11 items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)] ${CHROME_FOCUS}`}
            aria-label={state.isPinned ? 'Unpin' : 'Pin'}
          >
            <PinIcon size={20} className={state.isPinned ? 'opacity-100' : 'opacity-55'} />
          </button>
          <button
            type="button"
            onClick={editor.toggleArchive}
            className={`flex size-11 items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)] ${CHROME_FOCUS}`}
            aria-label={state.isArchived ? 'Unarchive' : 'Archive'}
          >
            <ArchiveIcon size={20} className={state.isArchived ? 'opacity-100' : 'opacity-55'} />
          </button>
        </div>
      </header>

      <div
        className="flex min-h-0 flex-1 flex-col overflow-y-auto px-layout-gap pt-4 sm:px-6 sm:pt-6 lg:px-8"
        style={{ paddingBottom: `calc(5.5rem + ${effectiveKeyboardInset}px)` }}
        onClick={(event) => {
          const target = event.target as HTMLElement;
          if (
            target.closest(
              'input, textarea, button, a, [aria-label="Text formatting"], [role="toolbar"]',
            )
          ) {
            return;
          }
          focusContentField();
        }}
      >
        <div className={`flex w-full flex-col ${writingColumnClass}`}>
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
                  onClick={() => void handleBack()}
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
          <input
            id="note-title"
            type="text"
            value={state.title}
            onChange={(event) => editor.setTitle(event.target.value)}
            placeholder="Title"
            maxLength={MAX_NOTE_TITLE_CHARS}
            className="w-full bg-transparent text-[24px] font-semibold leading-tight tracking-[-0.03em] outline-none placeholder:opacity-30 sm:text-[26px]"
            style={{ color: contentColor }}
          />

          {state.labels.length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-2">
              {state.labels.map((label) => (
                <span
                  key={label.id}
                  className="rounded-full px-2.5 py-1 text-xs font-medium tracking-wide"
                  style={{ backgroundColor: 'rgba(0,0,0,0.14)', color: contentColor }}
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
                onBold={() =>
                  applyFormatting((text, start, end) => wrapSelection(text, start, end, '**'))
                }
                onItalic={() =>
                  applyFormatting((text, start, end) => wrapSelection(text, start, end, '_'))
                }
                onBullet={() =>
                  applyFormatting((text, start, end) => prefixLinesWithBullet(text, start, end))
                }
                onChecklist={() => {
                  setContentFocused(true);
                  editor.convertContentToChecklist();
                }}
                onLink={() => {
                  rememberSelection();
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

              {contentFocused || !state.content.trim() ? (
                <textarea
                  ref={contentRef}
                  aria-label="Note body"
                  value={state.content}
                  onFocus={() => setContentFocused(true)}
                  onBlur={() => {
                    rememberSelection();
                    window.setTimeout(() => {
                      if (document.activeElement?.closest('[aria-label="Text formatting"]')) {
                        return;
                      }
                      setContentFocused(false);
                    }, 0);
                  }}
                  onSelect={rememberSelection}
                  onKeyUp={rememberSelection}
                  onMouseUp={rememberSelection}
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
                    selectionRef.current = {
                      start: result.selectionStart,
                      end: result.selectionEnd,
                    };
                    requestAnimationFrame(() => {
                      field.setSelectionRange(result.selectionStart, result.selectionEnd);
                    });
                  }}
                  placeholder="Start writing…"
                  rows={1}
                  maxLength={MAX_NOTE_CONTENT_CHARS}
                  className="mt-4 w-full min-h-[min(60vh,32rem)] resize-none overflow-hidden bg-transparent text-[17px] leading-[1.65] tracking-[0.005em] outline-none placeholder:opacity-30 sm:text-[18px]"
                  style={{ color: contentColor }}
                />
              ) : (
                <button
                  type="button"
                  onClick={() => {
                    focusContentField();
                  }}
                  aria-label="Edit note body"
                  className={`mt-4 w-full min-h-[min(60vh,32rem)] rounded-note text-left transition-opacity hover:opacity-95 ${CHROME_FOCUS}`}
                >
                  <MarkdownBody text={state.content} contentColor={contentColor} />
                </button>
              )}

              <button
                type="button"
                onClick={editor.convertContentToChecklist}
                className={`mt-10 self-start text-left text-sm font-medium opacity-60 transition-opacity hover:opacity-80 ${CHROME_FOCUS}`}
                style={{ color: contentColor }}
              >
                {state.content.trim() ? 'Convert to checklist' : '+ Add checklist'}
              </button>
            </>
          )}
        </div>
      </div>

      <div
        className="absolute inset-x-0 bottom-0 z-20"
        style={{ color: contentColor, bottom: effectiveKeyboardInset }}
      >
        <EditorBottomBar
          timestamp={state.timestamp}
          isSaving={state.isSaving}
          contentColor={contentColor}
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
        onDeleteNote={() => void handleDelete()}
        onShareNote={() => void handleShareNote()}
        onExportMarkdown={handleExportMarkdown}
      />
      <LinkDialog
        open={showLinkDialog}
        onCancel={() => setShowLinkDialog(false)}
        onConfirm={(url) => {
          applyFormatting((text, start, end) => wrapSelectionAsLink(text, start, end, url));
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
