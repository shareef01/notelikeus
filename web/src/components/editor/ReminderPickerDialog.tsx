import { useEffect, useId, useState } from 'react';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import {
  fromLocalDateTimeInputValue,
  initialReminderInputValue,
  reminderPresets,
} from '@/lib/reminders/reminderTime';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';

interface ReminderPickerDialogProps {
  open: boolean;
  initialTimestamp: number | null;
  onCancel: () => void;
  onConfirm: (timestamp: number) => void;
  onRemove: () => void;
}

export function ReminderPickerDialog({
  open,
  initialTimestamp,
  onCancel,
  onConfirm,
  onRemove,
}: ReminderPickerDialogProps) {
  const [value, setValue] = useState(() => initialReminderInputValue(initialTimestamp));
  const titleId = useId();
  const presetsLabelId = useId();
  const panelRef = useFocusTrap<HTMLDivElement>(open, onCancel);

  useEffect(() => {
    if (open) setValue(initialReminderInputValue(initialTimestamp));
  }, [open, initialTimestamp]);

  if (!open) return null;

  const parsed = fromLocalDateTimeInputValue(value);

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 animate-in fade-in duration-200 sm:items-center sm:p-6">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl animate-in zoom-in-95 duration-200"
      >
        <h2 id={titleId} className="text-lg font-semibold">
          Set reminder
        </h2>
        <p className="mt-2 text-xs text-brand-muted">
          Web reminders fire when this app is open or recently used. They can be late or miss
          if the browser is fully closed — unlike the Android app, which uses system alarms.
        </p>

        {/* The same three quick choices the Android and Windows dialogs offer, resolved by the
            shared arithmetic in reminderTime.ts so all three land on the same instant. Choosing
            one confirms directly: it is already an exact answer. */}
        <div
          role="group"
          aria-labelledby={presetsLabelId}
          className="mt-4 flex flex-wrap gap-2"
        >
          <span id={presetsLabelId} className="sr-only">
            Quick reminder choices
          </span>
          {reminderPresets().map((preset) => (
            <button
              key={preset.id}
              type="button"
              onClick={() => onConfirm(preset.at)}
              className={`rounded-full border border-brand-outline/50 px-3 py-1.5 text-sm text-brand-primary transition-colors hover:bg-brand-primary/10 ${CHROME_FOCUS}`}
            >
              {preset.label}
            </button>
          ))}
        </div>

        <label htmlFor={`${titleId}-at`} className="mt-5 block text-xs text-brand-muted">
          Or choose date &amp; time
        </label>
        <input
          id={`${titleId}-at`}
          type="datetime-local"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          className={`mt-1.5 w-full rounded-note border border-brand-outline/50 bg-transparent px-4 py-3 text-sm text-brand-primary outline-none focus:border-brand-primary/50 ${CHROME_FOCUS}`}
        />

        <div className="mt-5 flex justify-end gap-2">
          {initialTimestamp != null ? (
            <button
              type="button"
              onClick={onRemove}
              className={`mr-auto rounded-note px-4 py-2 text-sm text-red-400 transition-colors hover:bg-red-950/30 ${CHROME_FOCUS}`}
            >
              Remove
            </button>
          ) : null}
          <button
            type="button"
            onClick={onCancel}
            className={`rounded-note px-4 py-2 text-sm text-brand-muted transition-colors hover:text-brand-primary ${CHROME_FOCUS}`}
          >
            Cancel
          </button>
          <button
            type="button"
            // Disabled on an unparseable value rather than on an empty string: a half-typed
            // datetime-local reads as a non-empty value that `new Date` answers NaN for, and
            // confirming that used to set `reminderTimestamp` to NaN.
            onClick={() => parsed != null && onConfirm(parsed)}
            disabled={parsed == null}
            className={`rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-surface transition-transform disabled:opacity-40 active:scale-95 ${CHROME_FOCUS}`}
          >
            Set
          </button>
        </div>
      </div>
    </div>
  );
}
