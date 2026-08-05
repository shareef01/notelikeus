import { useEffect, useRef } from 'react';

function isEditableTarget(target: EventTarget | null): boolean {
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
  action: () => void;
}

/** Global keydown shortcuts. Bindings may be recreated every render — the latest
 * array is read from a ref, so the listener is registered exactly once. */
export function useShortcuts(bindings: ShortcutBinding[]): void {
  const latest = useRef(bindings);
  latest.current = bindings;

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      for (const binding of latest.current) {
        if (event.key !== binding.key) continue;
        if (binding.ctrlOrMeta) {
          if (!event.ctrlKey && !event.metaKey) continue;
          if (event.shiftKey || event.altKey) continue;
        } else if (event.ctrlKey || event.metaKey || event.altKey) {
          continue;
        }
        if (!binding.allowInInputs && isEditableTarget(event.target)) continue;
        event.preventDefault();
        binding.action();
        return;
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);
}
