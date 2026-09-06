import { useEffect, useRef } from 'react';

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

interface TrapEntry {
  getPanel: () => HTMLElement | null;
}

/**
 * Every currently open trap, in the order they opened.
 *
 * Each trap listens on `document`, and `stopPropagation()` does not stop the other listeners
 * already bound to that same node — so without an owner rule, one Escape ran every open trap's
 * `onClose`: dismissing the link dialog also closed the editor underneath it.
 */
const openTraps: TrapEntry[] = [];

/**
 * The trap that owns Escape: the most recently opened one that has no other open trap nested
 * inside it. Containment rather than push order, because React commits child effects before
 * parent ones — a dialog and its host opening in the same commit would otherwise hand Escape
 * to the host.
 */
function escapeOwner(): TrapEntry | null {
  for (let index = openTraps.length - 1; index >= 0; index--) {
    const entry = openTraps[index]!;
    const panel = entry.getPanel();
    if (!panel) continue;
    const wrapsAnotherTrap = openTraps.some((other) => {
      if (other === entry) return false;
      const otherPanel = other.getPanel();
      return otherPanel != null && panel.contains(otherPanel);
    });
    if (!wrapsAnotherTrap) return entry;
  }
  return null;
}

/**
 * Traps focus inside a dialog while `open`: focuses the first focusable element,
 * wraps Tab/Shift+Tab at the panel's edges, closes on Escape, and restores focus
 * to whatever triggered the dialog on close. Mirrors ResponsiveSheet's trap for
 * dialogs that don't use that component's sheet/modal layout.
 */
export function useFocusTrap<T extends HTMLElement>(
  open: boolean,
  onClose: () => void,
  options?: { closeOnEscape?: boolean },
) {
  const panelRef = useRef<T>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const closeOnEscape = options?.closeOnEscape ?? true;

  /**
   * Callers pass freshly-built closures — EditorScreen rebuilds `onFloatClose` on every
   * keystroke, because `useNoteEditor` returns a new object each render. Reading the handler
   * through a ref keeps this effect keyed on `open` alone. With `onClose` in the dependency
   * array the effect tore down and re-ran on every render, and its cleanup restores focus to
   * the opener before the re-run refocuses the panel's first element — so a single keystroke
   * moved the caret out of the note body and onto the back button.
   */
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) return;

    previousFocusRef.current = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    const focusables = panel?.querySelectorAll<HTMLElement>(FOCUSABLE);
    focusables?.[0]?.focus();

    const entry: TrapEntry = { getPanel: () => panelRef.current };
    openTraps.push(entry);

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (escapeOwner() !== entry) return;
        if (!closeOnEscape) {
          // Owning Escape and refusing it is the point of a non-dismissable trap: swallow the
          // key so a trap further out does not close instead.
          event.preventDefault();
          event.stopPropagation();
          return;
        }
        event.preventDefault();
        event.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;

      const items = panel.querySelectorAll<HTMLElement>(FOCUSABLE);
      if (items.length === 0) return;

      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      const index = openTraps.indexOf(entry);
      if (index !== -1) openTraps.splice(index, 1);
      document.removeEventListener('keydown', onKeyDown);
      previousFocusRef.current?.focus?.();
    };
  }, [open, closeOnEscape]);

  return panelRef;
}
