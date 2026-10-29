import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ReminderPickerDialog } from '@/components/editor/ReminderPickerDialog';
import { toLocalDateTimeInputValue } from '@/lib/reminders/reminderTime';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function renderPicker(initialTimestamp: number | null) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const onConfirm = vi.fn();
  act(() => {
    root.render(
      createElement(ReminderPickerDialog, {
        open: true,
        initialTimestamp,
        onCancel: vi.fn(),
        onConfirm,
        onRemove: vi.fn(),
      }),
    );
  });
  const setButton = Array.from(container.querySelectorAll('button')).find(
    (b) => b.textContent === 'Set',
  ) as HTMLButtonElement;
  return {
    container,
    onConfirm,
    setButton,
    cleanup: () => {
      act(() => root.unmount());
      container.remove();
    },
  };
}

describe('ReminderPickerDialog', () => {
  it('keeps the dialog open and explains a time that has already passed', () => {
    const { container, onConfirm, setButton, cleanup } = renderPicker(Date.now() - 86_400_000);

    expect(setButton.disabled).toBe(true);
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('That time has passed');
    expect(container.querySelector('input')?.getAttribute('aria-invalid')).toBe('true');
    act(() => setButton.click());
    expect(onConfirm).not.toHaveBeenCalled();
    cleanup();
  });

  it('sets a time in the future', () => {
    const future = Date.now() + 2 * 86_400_000;
    const { container, onConfirm, setButton, cleanup } = renderPicker(future);

    expect(setButton.disabled).toBe(false);
    expect(container.querySelector('[role="alert"]')).toBeNull();
    act(() => setButton.click());
    expect(onConfirm).toHaveBeenCalledTimes(1);
    // The input has minute precision, so the confirmed time is the minute the user sees.
    expect(toLocalDateTimeInputValue(onConfirm.mock.calls[0][0])).toBe(toLocalDateTimeInputValue(future));
    cleanup();
  });
});
