import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Whether a horizontally scrolling row has content beyond its right edge.
 *
 * The notes filters scroll sideways on a phone with their scrollbar hidden, so nothing told a user
 * that more colours or labels existed just off-screen. A fade is the cue, but only while it is
 * true: a permanent gradient over a row that already fits reads as a rendering artefact.
 *
 * Measured rather than inferred from class names, and re-measured whenever the row scrolls, the
 * window resizes, the element's own box changes, its children change (a new label appears), or the
 * component re-renders for any other reason — the combinations that matter are cheap to cover
 * together and one of them is always the one that would otherwise be missed.
 *
 * Guarded for environments without ResizeObserver (happy-dom under unit tests does not implement
 * it), where the scroll and resize listeners still keep the value honest.
 */
export function useOverflowHint<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [overflowing, setOverflowing] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);

  const measure = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    // 1px of slack: sub-pixel layout makes scrollWidth and clientWidth differ by a hair on rows
    // that visually fit, which would otherwise leave the cue on forever.
    setOverflowing(el.scrollWidth - el.clientWidth > 1);
    // A cue that says "more this way" has to go away when there is no more: measuring only
    // "content is wider than the box" left it showing after the row had been scrolled to its end.
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 1);
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    measure();

    el.addEventListener('scroll', measure, { passive: true });
    window.addEventListener('resize', measure);

    const ResizeObserverCtor =
      typeof globalThis.ResizeObserver === 'function' ? globalThis.ResizeObserver : null;
    const resizeObserver = ResizeObserverCtor ? new ResizeObserverCtor(measure) : null;
    resizeObserver?.observe(el);

    const MutationObserverCtor =
      typeof globalThis.MutationObserver === 'function' ? globalThis.MutationObserver : null;
    const mutationObserver = MutationObserverCtor
      ? new MutationObserverCtor(measure)
      : null;
    mutationObserver?.observe(el, { childList: true, subtree: true });

    return () => {
      el.removeEventListener('scroll', measure);
      window.removeEventListener('resize', measure);
      resizeObserver?.disconnect();
      mutationObserver?.disconnect();
    };
  }, [measure]);

  // Re-measure on every render as well: a caller that swaps row content without changing the
  // element's box would not trip either observer.
  useEffect(measure);

  return { ref, overflowing, canScrollRight };
}
