import { useEffect, useRef } from 'react';
import { isFocusTrapOpen } from '@/hooks/useFocusTrap';

export function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable;
}

export interface ShortcutBinding {
  /** KeyboardEvent.key, e.g. '/', 'n', 'Escape'. */
  key: string;
  /** Require Ctrl or Meta (either one). When set, Shift/Alt are disallowed. */
  ctrlOrMeta?: boolean;
  /** Allow the shortcut even while focus is inside an input/textarea/contenteditable. */
  allowInInputs?: boolean;
  /**
   * Return `false` when the shortcut does not apply right now. The key then goes on to later
   * bindings and to the browser instead of being swallowed — Ctrl+F with nothing of ours to focus
   * should still open the browser's find bar.
   */
  action: () => void | boolean;
}

export interface ShortcutOptions {
  /**
   * Stand aside while a dialog, sheet or overlay holds the keyboard: a key pressed there belongs
   * to it, not to the screen behind it.
   */
  ignoreWhileModalOpen?: boolean;
}

/** Global keydown shortcuts. Bindings may be recreated every render — the latest
 * array is read from a ref, so the listener is registered exactly once. */
export function useShortcuts(bindings: ShortcutBinding[], options?: ShortcutOptions): void {
  const latest = useRef(bindings);
  latest.current = bindings;
  const ignoreWhileModalOpen = options?.ignoreWhileModalOpen ?? false;

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (ignoreWhileModalOpen && isFocusTrapOpen()) return;
      for (const binding of latest.current) {
        if (event.key.toLowerCase() !== binding.key.toLowerCase()) continue;
        if (binding.ctrlOrMeta) {
          if (!event.ctrlKey && !event.metaKey) continue;
          if (event.shiftKey || event.altKey) continue;
        } else if (event.ctrlKey || event.metaKey || event.altKey) {
          continue;
        }
        if (!binding.allowInInputs && isEditableTarget(event.target)) continue;
        if (binding.action() === false) continue;
        event.preventDefault();
        return;
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [ignoreWhileModalOpen]);
}
