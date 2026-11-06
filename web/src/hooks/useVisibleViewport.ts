import { useEffect, useState } from 'react';

/** The rectangle above the software keyboard, including browser panning. */
export function useVisibleViewport() {
  const measure = () => ({height: window.visualViewport?.height ?? window.innerHeight,
    top: window.visualViewport?.offsetTop ?? 0});
  const [bounds, setBounds] = useState(measure);
  useEffect(() => {
    const update = () => {
      const next = measure();
      setBounds(previous => previous.height === next.height && previous.top === next.top ? previous : next);
    };
    window.addEventListener('resize', update);
    window.visualViewport?.addEventListener('resize', update);
    window.visualViewport?.addEventListener('scroll', update);
    return () => {
      window.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('scroll', update);
    };
  }, []);
  return bounds;
}
