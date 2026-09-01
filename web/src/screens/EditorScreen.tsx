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
import { EditorBottomBar } from '@/components/editor/EditorBottomBar';
import { EditorOptionsSheet } from '@/components/editor/EditorOptionsSheet';
import { LinkDialog } from '@/components/editor/LinkDialog';
import { MarkdownBody } from '@/components/editor/MarkdownPreview';
import { ReminderPickerDialog } from '@/components/editor/ReminderPickerDialog';
import { RichTextToolbar } from '@/components/editor/RichTextToolbar';
import { useNoteEditor } from '@/hooks/useNoteEditor';
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
import { noteSurfaceStyle } from '@/theme/contrast';
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

  const handleBack = useCallback(async () => {
    await editor.flushSave();
    closeEditor();
  }, [closeEditor, editor]);

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
          !state.title.trim() && !state.content.trim() && state.checklist.length === 0;
        if (isEmpty) return;
        void editor.flushSave();
        useToastStore.getState().show('Note saved');
      },
    },
  ]);
  const [showOptions, setShowOptions] = useState(false);
  const [showLinkDialog, setShowLinkDialog] = useState(false);
  const [showReminderPicker, setShowReminderPicker] = useState(false);
  const [contentFocused, setContentFocused] = useState(true);
  const contentRef = useRef<HTMLTextAreaElement>(null);
  const selectionRef = useRef({ start: 0, end: 0 });
  const isDarkPalette = useNotePaletteDark();
  const surface = noteSurfaceStyle(state.color, { solid: true, isDarkPalette });
  const contentColor =
    state.color === 0 ? 'rgb(var(--primary-rgb))' : surface.color;
  const hasChecklist = state.checklist.length > 0;
  const isFloatLayout = isTabletUp && editorLayout === 'float';
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
    await editor.trashNote();
    closeEditor();
  };

  const handleShareNote = async () => {
    const text = formatNoteForSharing(state.title, state.content, state.checklist);
    if (!text) return;
    if (typeof navigator !== 'undefined' && navigator.share) {
      try {
        await navigator.share({
          title: state.title || 'Note',
          text,
        });
        return;
      } catch (err) {
        if ((err as Error).name === 'AbortError') return;
      }
    }
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      await navigator.clipboard.writeText(text);
      useToastStore.getState().show('Note copied to clipboard');
    }
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
          className="fixed inset-0 z-40 flex flex-col"
          style={surface}
        >
          {children}
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

  const layoutControls = isTabletUp ? (
    <div
      className="flex h-9 shrink-0 items-center gap-0.5 rounded-full border border-[color-mix(in_srgb,currentColor_12%,transparent)] bg-[color-mix(in_srgb,currentColor_8%,transparent)] p-0.5"
      role="radiogroup"
      aria-label="Editor layout"
    >
      {EDITOR_LAYOUTS.map((button) => {
        const active = editorLayout === button.id;
        const Icon = button.icon;
        return (
          <button
            key={button.id}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => setEditorLayout(button.id)}
            className={`flex size-8 items-center justify-center rounded-full transition-[background-color,opacity] duration-150 ${CHROME_FOCUS} ${
              active
                ? 'bg-[color-mix(in_srgb,currentColor_18%,transparent)] opacity-100'
                : 'opacity-60 hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)] hover:opacity-80'
            }`}
            aria-label={button.label}
            title={button.label}
          >
            <Icon size={16} />
          </button>
        );
      })}
    </div>
  ) : null;


  return editorShell(
    <>
      <header
        className="flex items-center justify-between px-2 pt-safe lg:px-4"
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
        className="flex-1 overflow-y-auto px-layout-gap pt-5 sm:pt-6"
        style={{ paddingBottom: `calc(7rem + ${effectiveKeyboardInset}px)` }}
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
        <div className="flex min-h-full flex-col">
          <input
            type="text"
            value={state.title}
            onChange={(event) => editor.setTitle(event.target.value)}
            placeholder="Title"
            maxLength={MAX_NOTE_TITLE_CHARS}
            className="w-full bg-transparent text-[22px] font-semibold leading-snug tracking-[-0.03em] outline-none placeholder:opacity-35"
            style={{ color: contentColor }}
          />

          <div
            className="mt-4 h-px w-full opacity-[0.12]"
            style={{ backgroundColor: contentColor }}
            aria-hidden
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
              />

              {contentFocused || !state.content.trim() ? (
                <textarea
                  ref={contentRef}
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
                  className="mt-4 w-full min-h-52 resize-none overflow-hidden bg-transparent text-[17px] leading-[1.55] tracking-[0.01em] outline-none placeholder:opacity-35 sm:min-h-[320px] sm:text-[18px]"
                  style={{ color: contentColor }}
                />
              ) : (
                <button
                  type="button"
                  onClick={() => {
                    focusContentField();
                  }}
                  aria-label="Edit note body"
                  className={`mt-4 w-full min-h-52 rounded-note text-left transition-opacity hover:opacity-95 sm:min-h-[320px] ${CHROME_FOCUS}`}
                >
                  <MarkdownBody text={state.content} contentColor={contentColor} />
                </button>
              )}

              <button
                type="button"
                onClick={editor.convertContentToChecklist}
                className={`mt-auto pt-8 pb-1 text-left text-sm font-medium opacity-55 transition-opacity hover:opacity-90 ${CHROME_FOCUS}`}
                style={{ color: contentColor }}
              >
                {state.content.trim() ? 'Convert to checklist' : '+ Add checklist'}
              </button>
            </>
          )}
        </div>
      </div>

      <div
        className="absolute inset-x-0 bottom-0"
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
