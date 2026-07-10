import { useEffect, useState } from 'react';

interface ReminderPickerDialogProps {
  open: boolean;
  initialTimestamp: number | null;
  onCancel: () => void;
  onConfirm: (timestamp: number) => void;
  onRemove: () => void;
}

function toInputValue(timestamp: number | null): string {
  if (timestamp == null) {
    const nextHour = new Date();
    nextHour.setMinutes(0, 0, 0);
    nextHour.setHours(nextHour.getHours() + 1);
    return nextHour.toISOString().slice(0, 16);
  }
  const date = new Date(timestamp);
  const offset = date.getTimezoneOffset();
  const local = new Date(date.getTime() - offset * 60_000);
  return local.toISOString().slice(0, 16);
}

export function ReminderPickerDialog({
  open,
  initialTimestamp,
  onCancel,
  onConfirm,
  onRemove,
}: ReminderPickerDialogProps) {
  const [value, setValue] = useState(toInputValue(initialTimestamp));

  useEffect(() => {
    if (open) setValue(toInputValue(initialTimestamp));
  }, [open, initialTimestamp]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 sm:items-center sm:p-6">
      <div className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl">
        <h4 className="text-lg font-semibold">Set reminder</h4>
        <input
          type="datetime-local"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          className="mt-4 w-full rounded-note border border-brand-outline/50 bg-transparent px-4 py-3 text-sm text-brand-primary outline-none focus:border-brand-primary/50"
        />
        <div className="mt-5 flex justify-end gap-2">
          {initialTimestamp != null ? (
            <button
              type="button"
              onClick={onRemove}
              className="mr-auto rounded-note px-4 py-2 text-sm text-red-400"
            >
              Remove
            </button>
          ) : null}
          <button
            type="button"
            onClick={onCancel}
            className="rounded-note px-4 py-2 text-sm text-brand-muted"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => onConfirm(new Date(value).getTime())}
            disabled={!value}
            className="rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-black disabled:opacity-40"
          >
            Set
          </button>
        </div>
      </div>
    </div>
  );
}
