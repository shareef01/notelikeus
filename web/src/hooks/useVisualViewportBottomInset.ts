import { useEffect, useState } from 'react';

/**
 * Keyboard occlusion inset from the visual viewport (mobile Safari/Chrome).
 * Returns pixels the OS UI (usually the IME) covers at the bottom of the layout viewport.
 *
 * Deliberately *not* `- visualViewport.offsetTop`. The chrome this positions sits in a
 * `position: fixed` overlay, which is anchored to the layout viewport and does not move when the
 * visual viewport is scrolled — subtracting the scroll offset would make that chrome chase the
 * scroll position instead of staying put. The difference in *heights* answers the only question
 * being asked here: how much of the bottom of the page is something covering.
 *
 * The distinction is not academic. Instrumenting a failing mobile end-to-end run inside CI's own
 * browser image showed `visualViewport.height` pinned at 727 throughout — no keyboard, nothing
 * resizing — while `offsetTop` alternated 42/58 as the runner scrolled a button into view before
 * each click. Subtracting it turned that into an inset flipping between 16 and 0, dragging the
 * editor's action bar 16px back and forth: scrolling to reach a control was itself what moved the
 * control out from under the pointer.
 */
export function useVisualViewportBottomInset(): number {
  const [inset, setInset] = useState(0);

  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return;

    const update = () => {
      setInset(Math.max(0, Math.round(window.innerHeight - vv.height)));
    };

    update();
    vv.addEventListener('resize', update);
    vv.addEventListener('scroll', update);
    window.addEventListener('resize', update);
    return () => {
      vv.removeEventListener('resize', update);
      vv.removeEventListener('scroll', update);
      window.removeEventListener('resize', update);
    };
  }, []);

  return inset;
}
