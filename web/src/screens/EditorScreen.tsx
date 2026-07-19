import {
  ArchiveIcon,
  ArrowBackIcon,
  DockIcon,
  FloatWindowIcon,
  FullscreenIcon,
  LockIcon,
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
import { useAuthListener } from '@/hooks/useAuth';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import { useIsTabletUp } from '@/hooks/useMediaQuery';
import { useVisualViewportBottomInset } from '@/hooks/useVisualViewportBottomInset';
import {
  prefixLinesWithBullet,
  wrapSelection,
  wrapSelectionAsLink,
} from '@/lib/text/markdown';
import { isDeviceAuthAvailable, requireDeviceAuth } from '@/lib/auth/deviceAuth';
import { contentColorForBackground, noteSurfaceStyle } from '@/theme/contrast';
import { useUiStore, type EditorLayout, type EditorRoute } from '@/store/uiStore';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

interface EditorScreenProps {
  route: Exclude<EditorRoute, { mode: 'closed' }>;
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
  const [showOptions, setShowOptions] = useState(false);
  const [showLinkDialog, setShowLinkDialog] = useState(false);
  const [showReminderPicker, setShowReminderPicker] = useState(false);
  const [contentFocused, setContentFocused] = useState(true);
  const contentRef = useRef<HTMLTextAreaElement>(null);
  const selectionRef = useRef({ start: 0, end: 0 });
  const { user } = useAuthListener();
  const [deviceAuthAvailable, setDeviceAuthAvailable] = useState<boolean | null>(null);
  const [revealError, setRevealError] = useState<string | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);

  useEffect(() => {
    void isDeviceAuthAvailable().then(setDeviceAuthAvailable);
  }, []);

  const surface = noteSurfaceStyle(state.color, { solid: true });
  const contentColor = contentColorForBackground(state.color);
  const showLockOverlay = state.isLocked && !state.isAccessGranted;
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
    field.style.height = `${Math.max(field.scrollHeight, 160)}px`;
  }, [state.content, contentFocused, hasChecklist]);

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

  const handleBack = useCallback(async () => {
    await editor.flushSave();
    closeEditor();
  }, [closeEditor, editor]);

  const onFloatClose = useCallback(() => {
    void handleBack();
  }, [handleBack]);

  const floatPanelRef = useFocusTrap<HTMLDivElement>(isFloatLayout, onFloatClose);

  useEffect(() => {
    if (!isOverlayShell) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [isOverlayShell]);

  const handleDelete = async () => {
    await editor.trashNote();
    closeEditor();
  };

  const handleReveal = async () => {
    if (deviceAuthAvailable === null) return;
    if (deviceAuthAvailable === false) {
      editor.grantAccess();
      return;
    }
    setRevealError(null);
    setIsVerifying(true);
    try {
      await requireDeviceAuth(user?.uid ?? 'local', user?.email ?? 'Notelikeus');
      editor.grantAccess();
    } catch (error) {
      setRevealError(error instanceof Error ? error.message : 'Device verification failed.');
    } finally {
      setIsVerifying(false);
    }
  };

  const layoutButtons: { id: EditorLayout; label: string; icon: ReactNode }[] = [
    { id: 'float', label: 'Float note', icon: <FloatWindowIcon size={16} /> },
    { id: 'dock', label: 'Dock note', icon: <DockIcon size={16} /> },
    { id: 'fullscreen', label: 'Full screen', icon: <FullscreenIcon size={16} /> },
  ];

  const editorShell = (children: ReactNode) => {
    if (!isTabletUp) {
      return (
        <div className="fixed inset-0 z-40 flex flex-col bg-black/40">
          <div className="relative flex h-full w-full flex-col" style={surface}>
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
        <div className="fixed inset-0 z-40 flex flex-col" style={surface}>
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
      className="mr-1 flex h-9 items-center gap-0.5 rounded-full border border-current/15 bg-current/[0.07] p-1"
      role="group"
      aria-label="Editor layout"
    >
      {layoutButtons.map((button) => {
        const active = editorLayout === button.id;
        return (
          <button
            key={button.id}
            type="button"
            onClick={() => setEditorLayout(button.id)}
            className={`flex size-9 items-center justify-center rounded-full transition-[background-color,opacity] ${
              active
                ? 'bg-current/20 opacity-100'
                : 'opacity-45 hover:bg-current/10 hover:opacity-85'
            }`}
            aria-label={button.label}
            aria-pressed={active}
            title={button.label}
          >
            {button.icon}
          </button>
        );
      })}
    </div>
  ) : null;

  if (showLockOverlay) {
    return editorShell(
      <>
        <header className="flex items-center justify-between px-2 pt-safe lg:px-4">
          <button
            type="button"
            onClick={() => void handleBack()}
            className="flex size-11 items-center justify-center rounded-full transition-colors hover:bg-black/10"
            style={{ color: contentColor }}
            aria-label="Back"
          >
            <ArrowBackIcon size={22} />
          </button>
          <div className="flex items-center" style={{ color: contentColor }}>
            {layoutControls}
          </div>
        </header>
        <div className="flex flex-1 flex-col items-center justify-center gap-4 px-8 text-center">
          <span className="opacity-60" style={{ color: contentColor }}>
            <LockIcon size={48} />
          </span>
          <p className="text-xl font-semibold sm:text-2xl" style={{ color: contentColor }}>
            This note is hidden
          </p>
          <p className="max-w-xs text-sm opacity-70" style={{ color: contentColor }}>
            {deviceAuthAvailable === null
              ? 'Hidden notes are kept off your feed and out of cloud sync on this device.'
              : deviceAuthAvailable
                ? "Hidden notes are kept off your feed and out of cloud sync on this device. Verify with this device's screen lock to view it."
                : "Hidden notes are kept off your feed and out of cloud sync on this device. This browser can't verify your device lock, so anyone with access to it can reveal this note."}
          </p>
          {revealError ? (
            <p className="max-w-xs text-sm font-medium text-red-300 animate-in fade-in duration-200">
              {revealError}
            </p>
          ) : null}
          <button
            type="button"
            onClick={() => void handleReveal()}
            disabled={isVerifying || deviceAuthAvailable === null}
            className="flex items-center gap-2 rounded-note bg-black/15 px-6 py-3 text-sm font-semibold backdrop-blur-sm transition-opacity disabled:opacity-60"
            style={{ color: contentColor }}
          >
            {isVerifying ? (
              <span
                className="size-4 animate-spin rounded-full border-2 border-current/20 border-t-current"
                aria-hidden
              />
            ) : null}
            {isVerifying ? 'Verifying…' : 'Show note'}
          </button>
        </div>
      </>,
    );
  }

  return editorShell(
    <>
      <header
        className="flex items-center justify-between px-2 pt-safe lg:px-4"
        style={{ color: contentColor }}
      >
        <button
          type="button"
          onClick={() => void handleBack()}
          className="flex size-11 items-center justify-center rounded-full hover:bg-black/10"
          aria-label="Back"
        >
          <ArrowBackIcon size={22} />
        </button>

        <div className="flex items-center gap-1">
          {layoutControls}
          <button
            type="button"
            onClick={() => setShowReminderPicker(true)}
            className="flex size-11 items-center justify-center rounded-full hover:bg-black/10"
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
            className="flex size-11 items-center justify-center rounded-full hover:bg-black/10"
            aria-label={state.isPinned ? 'Unpin' : 'Pin'}
          >
            <PinIcon size={20} className={state.isPinned ? 'opacity-100' : 'opacity-55'} />
          </button>
          <button
            type="button"
            onClick={editor.toggleArchive}
            className="flex size-11 items-center justify-center rounded-full hover:bg-black/10"
            aria-label={state.isArchived ? 'Unarchive' : 'Archive'}
          >
            <ArchiveIcon size={20} className={state.isArchived ? 'opacity-100' : 'opacity-55'} />
          </button>
        </div>
      </header>

      <div
        className="flex-1 overflow-y-auto px-layout-gap pt-5 sm:pt-6"
        style={{ paddingBottom: `calc(7rem + ${effectiveKeyboardInset}px)` }}
      >
        <div className="flex min-h-full flex-col">
          <input
            type="text"
            value={state.title}
            onChange={(event) => editor.setTitle(event.target.value)}
            placeholder="Title"
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
                  className="mt-4 w-full min-h-40 resize-none overflow-hidden bg-transparent text-[16px] leading-[1.55] tracking-[0.01em] outline-none placeholder:opacity-35 sm:min-h-[280px]"
                  style={{ color: contentColor }}
                />
              ) : (
                <button
                  type="button"
                  onClick={() => {
                    setContentFocused(true);
                    requestAnimationFrame(() => contentRef.current?.focus());
                  }}
                  className="mt-4 w-full min-h-40 rounded-note text-left transition-opacity hover:opacity-95 sm:min-h-[280px]"
                >
                  <MarkdownBody text={state.content} contentColor={contentColor} />
                </button>
              )}

              <button
                type="button"
                onClick={editor.convertContentToChecklist}
                className="mt-auto pt-8 pb-1 text-left text-sm font-medium opacity-55 transition-opacity hover:opacity-90"
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
        isLocked={state.isLocked}
        onLockToggle={editor.toggleLock}
        onDeleteNote={() => void handleDelete()}
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
