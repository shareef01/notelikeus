import { useEffect, useRef, useState } from 'react';

/**
 * How long the occlusion must stay smaller before the UI follows it down.
 *
 * The inset positions the editor's floating action bar. A CI trace of the mobile editor caught the
 * raw value alternating between 0 and 16 across 57 consecutive DOM snapshots, which dragged the bar
 * back and forth 16px — and a control that moves between the moment a tap is aimed and the moment
 * it lands cannot be tapped at all. Six mobile end-to-end tests failed on exactly that, with the
 * scrolling content underneath reported as swallowing every click.
 *
 * Growth is applied at once, because occlusion appearing means something is now covering the
 * controls and they have to move immediately. Shrinkage waits, because that is the direction a
 * flickering measurement oscillates through, and being briefly too high is harmless while being
 * briefly too low is what puts a control under the fold. Ignoring the small values outright is not
 * an option: on a mobile layout viewport that is a few percent taller than the visible one, 16px
 * is exactly the correction that keeps the bar reachable.
 */
const INSET_SHRINK_SETTLE_MS = 400;

/**
 * Keyboard occlusion inset from the visual viewport (mobile Safari/Chrome).
 * Returns pixels the OS UI (usually the IME) covers at the bottom of the layout viewport.
 *
 * Follows occlusion up immediately and down only once the viewport settles, so a jittering
 * measurement cannot translate into jittering chrome. See [INSET_SHRINK_SETTLE_MS].
 */
export function useVisualViewportBottomInset(): number {
  const [inset, setInset] = useState(0);
  // Mirrors the rendered value so `update` can compare against it without a side effect inside a
  // state updater, which React is free to run more than once.
  const appliedRef = useRef(0);

  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return;

    let shrinkTimer: ReturnType<typeof setTimeout> | undefined;

    const measure = () =>
      Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop));

    const apply = (next: number) => {
      if (next === appliedRef.current) return;
      appliedRef.current = next;
      setInset(next);
    };

    const update = () => {
      const measured = measure();
      clearTimeout(shrinkTimer);
      if (measured >= appliedRef.current) {
        apply(measured);
        return;
      }
      // Smaller than what is on screen: hold, and only follow it down if it is still smaller once
      // the viewport has stopped moving. An oscillation never survives that wait.
      shrinkTimer = setTimeout(() => {
        const settled = measure();
        if (settled < appliedRef.current) apply(settled);
      }, INSET_SHRINK_SETTLE_MS);
    };

    update();
    vv.addEventListener('resize', update);
    vv.addEventListener('scroll', update);
    window.addEventListener('resize', update);
    return () => {
      clearTimeout(shrinkTimer);
      vv.removeEventListener('resize', update);
      vv.removeEventListener('scroll', update);
      window.removeEventListener('resize', update);
    };
  }, []);

  return inset;
}
