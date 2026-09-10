import { useEffect, useState } from 'react';

/**
 * The smallest occlusion worth moving the UI for.
 *
 * An on-screen keyboard takes a third of the screen — 150px on the smallest phone in use, usually
 * far more. Anything an order of magnitude below that is not a keyboard: it is the URL bar
 * settling, a scrollbar, or sub-pixel rounding in the visual viewport, and reacting to it moves
 * chrome around for no reason a user can perceive.
 *
 * It is also actively harmful. A CI trace of the mobile editor caught this value flip-flopping
 * between 0 and 16 across 57 consecutive DOM snapshots, which left the bottom action bar
 * oscillating between two positions 16px apart. Every attempt to tap "More options" computed a
 * point while the bar was in one position and landed while it was in the other, hitting the
 * scrolling content underneath — so the button was permanently unclickable while looking perfectly
 * normal in a screenshot. Six mobile end-to-end tests failed on exactly that.
 */
const KEYBOARD_OCCLUSION_MIN_PX = 120;

/**
 * Keyboard occlusion inset from the visual viewport (mobile Safari/Chrome).
 * Returns pixels the OS UI (usually the IME) covers at the bottom of the layout viewport.
 *
 * Quantised to keyboard scale, and with hysteresis, so a jittering viewport cannot translate into
 * jittering chrome. See [KEYBOARD_OCCLUSION_MIN_PX].
 */
export function useVisualViewportBottomInset(): number {
  const [inset, setInset] = useState(0);

  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return;

    const update = () => {
      const raw = Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop));
      const measured = raw < KEYBOARD_OCCLUSION_MIN_PX ? 0 : raw;
      // Hysteresis on top of the floor: a keyboard opening or closing clears this easily, while
      // the small oscillations that made the bar unclickable never do, so the bar holds still.
      setInset((previous) =>
        Math.abs(measured - previous) < KEYBOARD_OCCLUSION_MIN_PX ? previous : measured,
      );
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
