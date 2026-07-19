import { ColorSwatchRow } from '@/components/layout/ColorSwatch';
import { ResponsiveSheet } from '@/components/layout/ResponsiveSheet';
import { ModalScrim, modalPanelProps } from '@/components/layout/ModalScrim';
import { requestNotificationPermission } from '@/lib/reminders/reminderScheduler';
import { useToastStore } from '@/store/toastStore';
import { LockIcon, LockOpenIcon, TrashIcon, AddIcon } from '@/components/icons/Icons';
import { useFocusTrap } from '@/hooks/useFocusTrap';
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
  isLocked: boolean;
  onLockToggle: () => void;
  onDeleteNote: () => void;
}

/**
 * Editor Options Overhaul
 * Integrated with Elite Architecture and Premium Icons.
 */
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
  isLocked,
  onLockToggle,
  onDeleteNote,
}: EditorOptionsSheetProps) {
  const [newLabel, setNewLabel] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const confirmDeletePanelRef = useFocusTrap<HTMLDivElement>(confirmDelete, () => setConfirmDelete(false));

  if (!open) return null;

  const handleReminderInput = async (value: string) => {
    if (!value) {
      onReminderChange(null);
      return;
    }
    const nextTimestamp = new Date(value).getTime();
    if (Number.isNaN(nextTimestamp)) return;
    if (nextTimestamp <= Date.now()) {
      useToastStore.getState().show('Choose a future date and time', 'error');
      return;
    }
    const granted = await requestNotificationPermission();
    if (!granted) {
      useToastStore.getState().show('Enable notifications to use reminders', 'error');
      return;
    }
    onReminderChange(nextTimestamp);
  };

  return (
    <>
      <ResponsiveSheet open={open} onClose={onClose} ariaLabel="Note options" maxWidthClass="md:max-w-lg">
        <section className="px-4 py-2">
          <h3 className="text-[12px] font-semibold uppercase tracking-[0.8px] text-brand-muted/65">Color</h3>
          <div className="mt-3 flex flex-wrap gap-2">
            <ColorSwatchRow
              selectedColor={selectedColor}
              onSelect={(color) => color != null && onColorSelect(color)}
            />
          </div>
        </section>

        <section className="mt-4 border-t border-brand-outline/60 px-4 py-2">
          <h3 className="mt-4 text-[12px] font-semibold uppercase tracking-[0.8px] text-brand-muted/65">Labels</h3>
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
          <div className="mt-2 flex gap-2">
            <input
              type="text"
              value={newLabel}
              onChange={(event) => setNewLabel(event.target.value)}
              placeholder="New label"
              className="min-w-0 flex-1 rounded-note border border-brand-outline bg-true-surface-variant px-3 py-2 text-sm outline-none focus:border-brand-primary/40"
            />
            <button
              type="button"
              disabled={!newLabel.trim()}
              onClick={() => {
                onCreateLabel(newLabel);
                setNewLabel('');
              }}
              className="flex items-center gap-1 rounded-note bg-brand-primary px-4 py-2 text-sm font-bold text-true-surface disabled:opacity-40"
            >
              <AddIcon size={18} />
              Add
            </button>
          </div>
        </section>

        <section className="mt-4 border-t border-brand-outline/60 px-4 py-4">
          <h3 className="mt-2 text-[12px] font-semibold uppercase tracking-[0.8px] text-brand-muted/65">Reminder</h3>
          <p className="mt-2 text-sm text-brand-muted">{formatReminderLabel(reminderTimestamp)}</p>

          <div className="mt-4 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => onReminderChange(Date.now() + 3600000)}
              className="rounded-full border border-brand-outline px-3 py-1 text-xs font-medium text-brand-secondary interactive-hover"
            >
              In 1 hour
            </button>
            <button
              type="button"
              onClick={() => {
                const date = new Date();
                date.setDate(date.getDate() + 1);
                date.setHours(9, 0, 0, 0);
                onReminderChange(date.getTime());
              }}
              className="rounded-full border border-brand-outline px-3 py-1 text-xs font-medium text-brand-secondary interactive-hover"
            >
              Tomorrow morning
            </button>
            <button
              type="button"
              onClick={() => onReminderChange(Date.now() + 7 * 86400000)}
              className="rounded-full border border-brand-outline px-3 py-1 text-xs font-medium text-brand-secondary interactive-hover"
            >
              Next week
            </button>
          </div>

          <input
            type="datetime-local"
            value={formatReminderInputValue(reminderTimestamp)}
            onChange={(event) => void handleReminderInput(event.target.value)}
            className="mt-4 w-full rounded-note border border-brand-outline bg-true-surface-variant px-3 py-2 text-sm outline-none focus:border-brand-primary/40"
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
              onLockToggle();
              onClose();
            }}
            className="flex w-full items-center gap-4 px-4 py-4 text-left text-base text-brand-primary transition-colors interactive-hover"
          >
            {isLocked ? <LockOpenIcon size={24} className="text-brand-primary/60" /> : <LockIcon size={24} className="text-brand-primary/60" />}
            {isLocked ? 'Unhide note' : 'Hide note (this device only)'}
          </button>
          <button
            type="button"
            onClick={() => setConfirmDelete(true)}
            className="flex w-full items-center gap-4 px-4 py-4 text-left text-base text-red-400 transition-colors interactive-hover"
          >
            <TrashIcon size={24} className="text-red-400/70" />
            Delete note
          </button>
        </section>

        <div className="h-8" />
      </ResponsiveSheet>

      {confirmDelete ? (
        <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 animate-in fade-in duration-200 sm:items-center sm:p-6">
          <div
            ref={confirmDeletePanelRef}
            role="dialog"
            aria-modal="true"
            aria-label="Delete note?"
            className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl animate-in zoom-in-95 duration-200"
          >
            <h4 className="text-lg font-semibold">Delete note?</h4>
            <p className="mt-2 text-sm text-brand-muted">
              This note will be moved to trash on your synced devices.
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmDelete(false)}
                className="rounded-note px-4 py-2 text-sm text-brand-muted transition-colors hover:text-brand-primary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => {
                  setConfirmDelete(false);
                  onDeleteNote();
                  onClose();
                }}
                className="rounded-note bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700"
              >
                Delete
              </button>
            </div>
          </div>
        </ModalScrim>
      ) : null}
    </>
  );
}
