import { ColorSwatchRow } from '@/components/layout/ColorSwatch';
import { ResponsiveSheet } from '@/components/layout/ResponsiveSheet';
import { TrashIcon, AddIcon, ShareIcon, DownloadIcon } from '@/components/icons/Icons';
import { ConfirmDialog } from '@/components/ui/ModalDialog';
import { CHROME_FOCUS, INPUT_FOCUS } from '@/lib/ui/focusStyles';
import { reminderPresets } from '@/lib/reminders/reminderTime';
import type { Label } from '@/types/label';
import { useState } from 'react';

function formatReminderInputValue(timestamp: number | null): string {
  if (timestamp == null) return '';
  const date = new Date(timestamp);
  const offset = date.getTimezoneOffset();
  const local = new Date(date.getTime() - offset * 60_000);
  return local.toISOString().slice(0, 16);
}

function formatReminderLabel(timestamp: number | null): string {
  if (timestamp == null) return 'No reminder set';
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(timestamp));
}

interface EditorOptionsSheetProps {
  open: boolean;
  onClose: () => void;
  selectedColor: number;
  onColorSelect: (color: number) => void;
  allLabels: Label[];
  selectedLabels: Label[];
  onLabelToggle: (label: Label) => void;
  onCreateLabel: (name: string) => void;
  reminderTimestamp: number | null;
  onReminderChange: (timestamp: number | null) => void;
  onDeleteNote: () => void;
  onShareNote?: () => void;
  onExportMarkdown?: () => void;
}

export function EditorOptionsSheet({
  open,
  onClose,
  selectedColor,
  onColorSelect,
  allLabels,
  selectedLabels,
  onLabelToggle,
  onCreateLabel,
  reminderTimestamp,
  onReminderChange,
  onDeleteNote,
  onShareNote,
  onExportMarkdown,
}: EditorOptionsSheetProps) {
  const [newLabel, setNewLabel] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);

  if (!open) return null;

  // Permission + future-date checks live in useNoteEditor's setReminderTimestamp, shared with
  // the quick-pick chips below and the bell-icon dialog — not duplicated here.
  const handleReminderInput = (value: string) => {
    if (!value) {
      onReminderChange(null);
      return;
    }
    const nextTimestamp = new Date(value).getTime();
    if (Number.isNaN(nextTimestamp)) return;
    onReminderChange(nextTimestamp);
  };

  return (
    <>
      <ResponsiveSheet open={open} onClose={onClose} ariaLabel="Note options" maxWidthClass="md:max-w-lg" closeOnEscape={!confirmDelete}>
        <section className="px-4 py-2">
          <h3 className="text-chrome-label">Color</h3>
          <div className="mt-3 flex flex-wrap gap-2">
            <ColorSwatchRow
              selectedColor={selectedColor}
              onSelect={(color) => color != null && onColorSelect(color)}
              wrap
            />
          </div>
        </section>

        <section className="mt-4 border-t border-brand-outline/60 px-4 py-2">
          <h3 className="mt-4 text-chrome-label">Labels</h3>
          <ul className="mt-2">
            {allLabels.map((label) => {
              const checked = selectedLabels.some((entry) => entry.id === label.id);
              return (
                <li key={label.id}>
                  <label className="flex min-h-12 cursor-pointer items-center gap-3 py-2">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => onLabelToggle(label)}
                      className="size-5 rounded accent-brand-primary"
                    />
                    <span className="text-base text-brand-primary">{label.name}</span>
                  </label>
                </li>
              );
            })}
          </ul>
          {/* A form, so Enter in the field adds the label as the button does. */}
          <form
            className="mt-2 flex gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              if (!newLabel.trim()) return;
              onCreateLabel(newLabel);
              setNewLabel('');
            }}
          >
            <input
              type="text"
              value={newLabel}
              onChange={(event) => setNewLabel(event.target.value)}
              placeholder="New label"
              aria-label="New label name"
              className={`min-w-0 flex-1 rounded-note border border-brand-outline bg-true-surface-variant px-3 py-2 text-sm ${INPUT_FOCUS}`}
            />
            <button
              type="submit"
              disabled={!newLabel.trim()}
              className={`flex items-center gap-1 rounded-note bg-brand-primary px-4 py-2 text-sm font-bold text-true-surface disabled:opacity-40 ${CHROME_FOCUS}`}
            >
              <AddIcon size={18} />
              Add
            </button>
          </form>
        </section>

        <section className="mt-4 border-t border-brand-outline/60 px-4 py-4">
          <h3 className="mt-2 text-chrome-label">Reminder</h3>
          <p className="mt-2 text-sm text-brand-muted">{formatReminderLabel(reminderTimestamp)}</p>
          <p className="mt-2 text-xs leading-5 text-brand-muted">
            Web reminders are best-effort. They can be delayed or missed if the browser is fully
            closed or inactive.
          </p>

          <div className="mt-4 flex flex-wrap gap-2">
            {reminderPresets().map((preset) => (
              <button
                key={preset.id}
                type="button"
                onClick={() => onReminderChange(preset.at)}
                // tap-target-y: these sit at 26px because the row is dense, but they are the fastest
                  // thing in the reminder flow to reach for, so the target is 44px tall while the
                  // chip keeps its size.
                  className="tap-target tap-target-y rounded-full border border-brand-outline px-3 py-1 text-xs font-medium text-brand-secondary hover:bg-brand-primary/5"
              >
                {preset.label}
              </button>
            ))}
          </div>

          <input
            type="datetime-local"
            value={formatReminderInputValue(reminderTimestamp)}
            onChange={(event) => handleReminderInput(event.target.value)}
            aria-label="Reminder date and time"
            className={`tap-target tap-target-y mt-4 w-full rounded-note border border-brand-outline bg-true-surface-variant px-3 py-2 text-sm ${INPUT_FOCUS}`}
          />
          {reminderTimestamp != null ? (
            <button
              type="button"
              onClick={() => onReminderChange(null)}
              className="mt-3 text-sm font-medium text-brand-muted hover:text-brand-primary transition-colors"
            >
              Clear reminder
            </button>
          ) : null}
        </section>

        <section className="mt-4 border-t border-brand-outline/60">
          <button
            type="button"
            onClick={() => {
              onShareNote?.();
              onClose();
            }}
            className="flex w-full items-center gap-4 px-4 py-4 text-left text-base text-brand-primary transition-colors hover:bg-brand-primary/5"
          >
            <ShareIcon size={24} className="text-brand-muted" />
            Share note
          </button>
          <button
            type="button"
            onClick={() => {
              onExportMarkdown?.();
              onClose();
            }}
            className="flex w-full items-center gap-4 px-4 py-4 text-left text-base text-brand-primary transition-colors hover:bg-brand-primary/5"
          >
            <DownloadIcon size={24} className="text-brand-muted" />
            Download as Markdown (.md)
          </button>
          <button
            type="button"
            onClick={() => setConfirmDelete(true)}
            className="flex w-full items-center gap-4 px-4 py-4 text-left text-base text-red-700 transition-colors dark:text-red-400 hover:bg-brand-primary/5"
          >
            <TrashIcon size={24} className="text-red-700/80 dark:text-red-400/70" />
            Delete note
          </button>
        </section>

        <div className="h-8" />
      </ResponsiveSheet>

      <ConfirmDialog
        open={confirmDelete}
        title="Delete note?"
        description="This note will be moved to trash on your synced devices."
        confirmLabel="Delete"
        onCancel={() => setConfirmDelete(false)}
        onConfirm={() => {
          setConfirmDelete(false);
          onDeleteNote();
          onClose();
        }}
      />
    </>
  );
}
