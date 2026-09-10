import { useEffect } from 'react';

/**
 * Prevents the page behind an overlay from scrolling while `locked`, restoring the previous values.
 *
 * Locks the document element as well as the body, which is not belt-and-braces: `overflow: hidden`
 * on `<body>` alone leaves `<html>` scrollable, and on a mobile viewport that is enough to break
 * the overlay on top of it. Instrumenting the failing mobile end-to-end run inside CI's own browser
 * image showed `documentElement` at `overflow: visible` with a 424x785 scroll area inside a 393x727
 * viewport — so the document could still pan. Panning moves the visual viewport under a
 * `position: fixed` overlay, which is anchored to the layout viewport and does not move with it, so
 * every attempt to tap a control in the editor's action bar scrolled the page first and then landed
 * somewhere the control no longer was. Six tests failed on that, and the controls looked perfectly
 * placed in every screenshot.
 */
export function useBodyScrollLock(locked: boolean) {
  useEffect(() => {
    if (!locked) return;
    const root = document.documentElement;
    const previousBodyOverflow = document.body.style.overflow;
    const previousRootOverflow = root.style.overflow;
    document.body.style.overflow = 'hidden';
    root.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousBodyOverflow;
      root.style.overflow = previousRootOverflow;
    };
  }, [locked]);
}
